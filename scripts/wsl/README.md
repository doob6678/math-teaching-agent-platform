# WSL user Compose service

在 Ubuntu WSL 已启用 `systemd` 时，`compose-stack-service.sh` 将本仓库的唯一 Compose owner 安装为当前 Linux 用户的 `systemd --user` unit：`~/.config/systemd/user/math-agent-rag-compose.service`。不需要 `sudo`，不会读取、复制或输出 `.env` 的密钥。

在 WSL 的仓库根目录执行：

```bash
scripts/wsl/compose-stack-service.sh install
scripts/wsl/compose-stack-service.sh start
scripts/wsl/compose-stack-service.sh status
```

启动命令固定为 `docker compose --env-file .env up -d --no-recreate`。它不会执行 `docker compose down`，不会重建已有容器，且不得与 IDE、Docker Desktop 或其他本地启动器并行使用。验收前该 owner 应为 active；验收 runner 对 backend/ai-worker 稳定容器 ID、`RestartCount` 和 readiness gate 仍是强制门禁，不能由该服务替代。

`logs` 读取当前用户的 journal；`stop` 仅停止 user unit 的 Compose owner 标记，不会停止 Docker 或任何容器。服务可在启动它的 WSL shell 退出后继续由用户 manager 保持。它不能跨完整 WSL 关闭、Linux 停机、Windows 重启、主机关闭或 user manager 退出继续运行；当前用户未启用 linger 时尤其如此。
