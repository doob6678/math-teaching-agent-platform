#!/usr/bin/env bash
set -euo pipefail

# 在 WSL 中启动 Compose 前执行此脚本。它只校验部署输入，不读取或输出密钥内容。
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ENV_FILE=${1:-"$ROOT_DIR/.env"}

if [[ ! -f "$ENV_FILE" ]]; then
  printf '缺少部署环境文件: %s\n' "$ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

failures=0

fail() {
  printf '失败: %s\n' "$1" >&2
  failures=$((failures + 1))
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "未找到命令 $1"
}

require_value() {
  local name=$1
  [[ -n "${!name:-}" ]] || fail "环境变量 $name 未设置"
}

require_directory() {
  local name=$1
  local path=${!name:-}
  [[ -n "$path" && -d "$path" ]] || fail "$name 不是可访问目录: ${path:-<empty>}"
}

require_file() {
  local name=$1
  local path=${!name:-}
  [[ -n "$path" && -f "$path" ]] || fail "$name 不是可访问文件: ${path:-<empty>}"
}

check_port() {
  local port=$1
  if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :$port" | grep -q LISTEN; then
    fail "端口 $port 已被占用"
  fi
}

printf '检查 WSL 发布前置条件...\n'
require_command docker
require_command nvidia-smi

if ! docker info >/dev/null 2>&1; then
  fail 'Docker daemon 不可用'
fi
if ! docker compose version >/dev/null 2>&1; then
  fail 'Docker Compose v2 不可用'
fi
if ! nvidia-smi --query-gpu=name --format=csv,noheader >/dev/null 2>&1; then
  fail 'WSL 未检测到可用 NVIDIA GPU'
fi

for name in \
  OPENAI_API_KEY \
  MYSQL_ROOT_PASSWORD \
  MATH_AGENT_AI_RUNTIME_DB_PASSWORD \
  REDIS_PASSWORD \
  RABBITMQ_DEFAULT_USER \
  RABBITMQ_DEFAULT_PASS \
  MATH_AGENT_FEISHU_TOKEN_ENCRYPTION_KEY \
  MATH_AGENT_WORKER_API_KEY \
  MATH_AGENT_AGENT_WORKER_SHARED_KEY \
  MATH_AGENT_MINIO_ACCESS_KEY \
  MATH_AGENT_MINIO_SECRET_KEY \
  MATH_AGENT_MILVUS_ROOT_PASSWORD; do
  require_value "$name"
done

require_directory MATH_AGENT_MODEL_ROOT
require_directory MATH_AGENT_PROCESSED_BOOKS_HOST_ROOT
require_directory MATH_AGENT_GAOKAO_INPUT_HOST_ROOT
require_file MATH_AGENT_PDF_FONT_HOST_PATH

for model_dir in \
  BAAI/bge-small-zh-v1.5 \
  BAAI/bge-reranker-v2-m3 \
  damo/multi-modal_clip-vit-large-patch14_zh; do
  [[ -d "$MATH_AGENT_MODEL_ROOT/$model_dir" ]] || fail "缺少本地模型目录: $MATH_AGENT_MODEL_ROOT/$model_dir"
done

[[ -f "$MATH_AGENT_PROCESSED_BOOKS_HOST_ROOT/_section_bge_index/manifest.json" ]] \
  || fail '教材目录缺少 _section_bge_index/manifest.json'

for pdf in \
  '2024年高考数学试卷（北京）（空白卷）.pdf' \
  '2024年高考数学试卷（北京）（解析卷）.pdf' \
  '2024年高考数学试卷（新课标Ⅰ卷）（空白卷）.pdf' \
  '2024年高考数学试卷（新课标Ⅰ卷）（解析卷）.pdf' \
  '2024年高考数学试卷（新课标Ⅱ卷）（空白卷）.pdf' \
  '2024年高考数学试卷（新课标Ⅱ卷）（解析卷）.pdf'; do
  [[ -f "$MATH_AGENT_GAOKAO_INPUT_HOST_ROOT/$pdf" ]] || fail "Gaokao 输入目录缺少批准 PDF: $pdf"
done

for port in \
  "${MATH_AGENT_BACKEND_HOST_PORT:-8080}" \
  "${MATH_AGENT_FRONTEND_HOST_PORT:-5173}" \
  "${MATH_AGENT_WORKER_HOST_PORT:-8092}" \
  "${MATH_AGENT_MYSQL_HOST_PORT:-3307}" \
  "${MATH_AGENT_REDIS_HOST_PORT:-6380}" \
  "${MATH_AGENT_RABBITMQ_HOST_PORT:-5674}" \
  "${MATH_AGENT_RABBITMQ_MANAGEMENT_HOST_PORT:-15674}" \
  "${MATH_AGENT_MILVUS_HOST_PORT:-19531}"; do
  check_port "$port"
done

if ! (cd "$ROOT_DIR" && docker compose --env-file "$ENV_FILE" config -q); then
  fail 'Docker Compose 配置插值失败'
fi

if ((failures > 0)); then
  printf '预检失败，共 %d 项。\n' "$failures" >&2
  exit 1
fi

printf '预检通过：WSL、GPU、挂载路径、批准 PDF、端口和 Compose 配置均可启动。\n'
