#!/usr/bin/env bash
set -euo pipefail

# 在 WSL 当前用户的 systemd manager 中管理本仓库唯一 Compose owner；不读取或输出 .env 密钥。
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly UNIT_NAME="math-agent-rag-compose.service"
readonly UNIT_TEMPLATE="${SCRIPT_DIR}/${UNIT_NAME}"
readonly USER_UNIT_DIRECTORY="${XDG_CONFIG_HOME:-${HOME}/.config}/systemd/user"
readonly INSTALLED_UNIT="${USER_UNIT_DIRECTORY}/${UNIT_NAME}"

fail() {
  printf '失败: %s\n' "$1" >&2
  exit 1
}

require_user_systemd() {
  [[ "$(ps -p 1 -o comm= | tr -d '[:space:]')" == "systemd" ]] \
    || fail '当前 WSL 未以 systemd 运行，不能使用 user service。'
  command -v systemctl >/dev/null 2>&1 || fail '未找到 systemctl。'
  systemctl --user show-environment >/dev/null 2>&1 \
    || fail '当前用户的 systemd --user manager 不可用。'
}

require_compose_inputs() {
  command -v docker >/dev/null 2>&1 || fail '未找到 docker。'
  docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 不可用。'
  [[ -f "${REPOSITORY_ROOT}/.env" ]] || fail '缺少仓库 .env 文件。'
}

install_unit() {
  require_user_systemd
  require_compose_inputs
  [[ -f "$UNIT_TEMPLATE" ]] || fail "缺少服务模板: $UNIT_TEMPLATE"

  # 模板只替换仓库绝对路径；不展开 .env，也不将凭据写入 unit 或日志。
  local escaped_root
  escaped_root=$(printf '%s' "$REPOSITORY_ROOT" | sed 's/[&|\\]/\\&/g')
  mkdir -p "$USER_UNIT_DIRECTORY"
  sed "s|@REPOSITORY_ROOT@|${escaped_root}|g" "$UNIT_TEMPLATE" > "$INSTALLED_UNIT"
  chmod 0644 "$INSTALLED_UNIT"
  systemctl --user daemon-reload
  systemctl --user enable "$UNIT_NAME" >/dev/null
  printf '已安装并启用 WSL user Compose owner: %s\n' "$UNIT_NAME"
}

start_unit() {
  require_user_systemd
  require_compose_inputs
  [[ -f "$INSTALLED_UNIT" ]] || fail "服务尚未安装；先执行: $0 install"
  # systemd 的 ExecStart 使用 --no-recreate，不会因该操作重建已有容器。
  systemctl --user start "$UNIT_NAME"
  printf 'WSL user Compose owner 已启动。\n'
}

status_unit() {
  require_user_systemd
  require_compose_inputs
  if [[ ! -f "$INSTALLED_UNIT" ]]; then
    printf 'WSL user Compose owner 未安装。\n'
    return 1
  fi

  systemctl --user --no-pager --full status "$UNIT_NAME"
  (cd "$REPOSITORY_ROOT" && docker compose --env-file .env ps --format 'table {{.Name}}\t{{.Status}}')
}

logs_unit() {
  require_user_systemd
  [[ -f "$INSTALLED_UNIT" ]] || fail "服务尚未安装；先执行: $0 install"
  # unit 日志仅包含 docker compose 调用输出，不读取或显示 .env 内容。
  journalctl --user --no-pager -u "$UNIT_NAME" --since 'today'
}

stop_unit() {
  require_user_systemd
  [[ -f "$INSTALLED_UNIT" ]] || fail "服务尚未安装；先执行: $0 install"
  # unit 没有 ExecStop；此命令不会停止 Docker、Compose 容器或删除任何数据。
  systemctl --user stop "$UNIT_NAME"
  printf 'WSL user Compose owner 已停止管理；现有容器保持当前状态。\n'
}

usage() {
  printf '用法: %s {install|start|status|logs|stop}\n' "$0" >&2
}

case "${1:-}" in
  install) install_unit ;;
  start) start_unit ;;
  status) status_unit ;;
  logs) logs_unit ;;
  stop) stop_unit ;;
  *) usage; exit 2 ;;
esac
