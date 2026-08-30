# WSL user Compose service

在 Ubuntu WSL 已启用 `systemd` 时，项目的唯一 Compose owner 由已安装的 system-level unit `math-agent-rag-compose.service` 管理。`compose-stack-service.sh` 仍保留为 user-unit 安装脚本，但检测到 `/etc/systemd/system/math-agent-rag-compose.service` 时会拒绝启动 user owner，避免两个 Compose 客户端竞争容器。无需读取、复制或输出 `.env` 的密钥。

在 WSL 的仓库根目录执行：

```bash
scripts/wsl/compose-stack-service.sh install
scripts/wsl/compose-stack-service.sh start
scripts/wsl/compose-stack-service.sh status
```

启动命令固定为 `docker compose --env-file .env up -d --no-recreate --wait --wait-timeout 300`。`--wait` 会先等待依赖和应用 healthcheck，不会执行 `docker compose down`，不会重建已有容器；且不得与 IDE、Docker Desktop 或其他本地启动器并行使用。验收前该 owner 应为 active；验收 runner 对 backend/ai-worker 稳定容器 ID、`RestartCount` 和 readiness gate 仍是强制门禁，不能由该服务替代。

注意：WSL 实例如果没有宿主保持进程，最后一个 `wsl.exe ... bash -lc` 退出后可能被 Windows 回收，systemd、Docker 和全部容器会同时重新启动。这不是 Compose 重建。验收期间必须让 Ubuntu WSL 有一个持续后台进程（例如后台 `wsl.exe -d Ubuntu -- bash -lc 'exec sleep infinity'`），并禁止任何脚本调用 `wsl --shutdown`；否则 Docker daemon 的生命周期会打断稳定窗口。项目另有旧的 `milvus-containers.service` 时必须保持 disabled，它会启动另一个项目的 `milvus-*` 容器并干扰当前栈。

`logs` 读取当前用户的 journal；`stop` 仅停止 user unit 的 Compose owner 标记，不会停止 Docker 或任何容器。服务可在启动它的 WSL shell 退出后继续由用户 manager 保持。它不能跨完整 WSL 关闭、Linux 停机、Windows 重启、主机关闭或 user manager 退出继续运行；当前用户未启用 linger 时尤其如此。
