# Local startup scripts

These scripts start only real services. They do not switch the backend into no-database mode.

## Compose ownership during acceptance

真实讲义/PDF 验收只能由一个 Compose owner 管理：先由一个终端执行一次 `docker compose up -d`，之后不得并发运行会调用 `docker compose up/down/restart` 的本目录启动脚本、IDE 自动部署或 Docker Desktop 重建。`start-all.ps1`、`start-backend.ps1` 和 `start-worker.ps1` 面向独立本机服务，不得与 Compose 栈同时负责同一服务。验收 runner 启动、轮询和导出前会检查 backend/ai-worker 的健康状态、容器 ID 和 `RestartCount`，容器重建或连接拒绝时等待新的稳定窗口，不重复提交任务。

验收期间不得执行 `docker compose down/up`，也不得并行启动本地启动器。先在 WSL 运行 `scripts/wsl/compose-stack-service.sh install` 和 `scripts/wsl/compose-stack-service.sh start`，由当前 Linux 用户的 systemd service 成为唯一 Compose owner。该服务仅使用 `docker compose --env-file .env up -d --no-recreate`，不会停止或重建容器；它不能跨完整 WSL 关闭、Linux 停机、Windows 重启或主机关闭。验收 runner 的稳定 ID/readiness gate 仍为提交任务前、轮询前和导出前的强制门禁。

runner 默认使用宿主机 `http://127.0.0.1:8080`，也可显式设置 `MATH_AGENT_ACCEPTANCE_BASE_URL`；在 Compose 网络内运行时使用 `http://backend:8080`，不改变公开 URL、DNS 或端口配置。

## Python MCP handout acceptance runner

`run_handout_mcp_acceptance.py` runs one real, source-grounded MCP handout acceptance flow using Python standard-library JSON and HTTP handling, so Windows callers do not need PowerShell JSON quoting.

```powershell
python .\scripts\local\run_handout_mcp_acceptance.py --preflight-only
python .\scripts\local\run_handout_mcp_acceptance.py --topic parabola --run-label handout-mcp-20260817-a
```

`--topic` accepts `parabola`, `hyperbola`, or `independence-test`; without it, topic selection rotates deterministically from `--run-label`. `--base-url`, `--timeout`, `--http-timeout`, `--poll-interval-seconds`, and `--stability-sample-seconds` configure the client. `--preflight-only` requires the WSL user unit `math-agent-rag-compose.service`, takes two backend/ai-worker samples separated by 30 seconds, checks unchanged IDs, `RestartCount=0`, healthy Compose state, and `/api/system/health` `UP`. It never logs in, creates an MCP key, or submits a task.

A normal run writes `output/acceptance/handout-mcp/<run-label>/`: redacted `acceptance.json`, a non-secret `submission-correlation.json`, HTTP/timeline and task-status evidence, exported teacher/student/lecture PDFs, SHA-256, extracted text, and Poppler-rendered page PNGs. A `WAITING_REVIEW` workflow exits as review-required; the runner never approves it automatically. The rendered pages still require human review against the handout architecture checklist.

Credentials are read only from the existing process environment or existing `.env`, never printed or copied. The temporary MCP key is held only in memory from the returned `secretKey` field, redacted from artifacts, and revoked in `finally`; never place it in `.env`, README output, or evidence. Every invocation creates a fresh idempotency correlation and sends exactly one workflow-start POST. On uncertainty, it persists the correlation and never retries submission; query only that same task after its ID is known. Cache or memory-reuse signals fail the freshness gate.

1. `start-prerequisites.ps1`
   - Starts/checks WSL `Ubuntu`.
   - Verifies Redis at `127.0.0.1:6379`.
   - Verifies MySQL at `127.0.0.1:3306`.
   - Starts existing Milvus Docker containers: `milvus-etcd`, `milvus-minio`, `milvus-standalone`.
   - Creates the MySQL application user only when `MATH_AGENT_DB_ROOT_PASSWORD` is set.
   - Verifies Windows can reach `3306`, `6379`, and `19530` before backend startup.

2. `start-all.ps1`
   - Runs prerequisite checks, then starts worker, backend, and frontend as hidden PowerShell processes.
   - Writes logs to `output/local-services/`.
   - Does not create missing Milvus containers or fake any dependency readiness.
   - Skips duplicate startup when `8091`, `8080`, or `5173/5174` are already listening.
   - Checks WSL services first, then starts the shared WSL proxy fallback on `13306`, `16379`, and `19531` when Windows cannot reach standard local service ports.

3. `status.ps1`
   - Reports ports for frontend, backend, worker, and WSL proxies.
   - Treats a proxy as reachable when either `127.0.0.1:<proxy-port>` or the current WSL IP `<proxy-port>` is reachable, and includes `proxyRoutes` so the actual route is visible.
   - Reads the local worker key from `.local-secrets/worker-api-key.txt` and checks real worker capabilities.
   - Logs in with a local admin account only to read `/api/system/runtime`; it does not mutate data or restart services.

4. `start-worker.ps1`
   - Starts `ai-worker-python` as an embedding API.
   - Skips duplicate startup when `127.0.0.1:8091` is already listening.
   - Supports `-Background` to start the worker as a hidden process and write logs to `output/local-services/`.
   - Uses local CLIP by default with 512-dimensional vectors.
   - Auto-detects a local BGE reranker when `D:\ModelScope\models\BAAI\bge-reranker-v2-m3`, `D:\ModelScope\models\BAAI\bge-reranker-base`, or the corresponding HuggingFace cache snapshots already exist.
   - Reads `MATH_AGENT_WORKER_API_KEY` when set; otherwise creates `.local-secrets/worker-api-key.txt` and reuses it.
   - Does not install Python packages automatically. Missing dependencies fail fast.
   - Auto-detects `D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_zh` when present.
   - Text embeddings can run directly from local ModelScope weights with real `torch` and `transformers`.
   - Image embeddings and CLIP similarity now load the local ModelScope `module.visual.*` weights directly, so they do not require `addict`.
   - Exposes `/v1/rerank` when a local BGE reranker is available, so backend two-stage retrieval can use a real cross-encoder style rerank instead of only cosine similarity.

5. `start-backend.ps1`
   - Starts Spring Boot with MySQL, Redis, Milvus, and real embedding configuration.
   - Defaults to `http://127.0.0.1:8091/v1` and the same `.local-secrets/worker-api-key.txt` key used by the worker.
   - Prefers the current WSL proxy host ports `13306/16379/19531`, then localhost proxy ports, then localhost standard ports only as a fallback.
   - Sets both Redisson and Spring Data Redis URLs to the same resolved Redis route.
   - Creates/reuses `.local-secrets/mcp-secret.txt`, registers only its SHA-256 hash with the backend, and enables local WorkBuddy admin MCP tools that have real execution endpoints for tenant `school-a`.

6. `start-frontend.ps1`
   - Starts Vite on the next available local port starting from `http://127.0.0.1:5173/`.

7. `start-mysql-proxy.ps1`, `start-redis-proxy.ps1`, `start-milvus-proxy.ps1`
   - Start one Windows-to-WSL TCP proxy using the shared `tcp-proxy.py`.
   - Default to standard local ports `3306`, `6379`, and `19530`.
   - Fail fast when Python, WSL IP resolution, or the proxy script is unavailable.

8. `rebuild-all-teacher-resource-indexes.ps1`
   - Logs in to the backend, verifies vector index runtime, lists synced and parsed teacher resources, then calls the backend rebuild endpoint for each resource.
   - Uses only backend APIs; it does not write Milvus directly.
   - Supports `-DryRun` to verify login, runtime status, and target resource selection without calling local CLIP or Milvus upsert.

9. `register-teacher-resource.ps1`
   - Registers one real local teacher resource through the backend user-session flow.
   - By default stages one existing PDF from the workspace into `.local-storage/seed-resources/` using an ASCII path, then creates and executes a sync job.
   - Uses only backend APIs; it does not write `source_document`, `document_block`, or Milvus directly.

10. `archive-teacher-resource.ps1`
   - Archives one teacher resource through the backend user-session flow.
   - The backend now deletes that document's vectors from Milvus during archive, preventing archived resources from polluting future search.

11. `reseed-knowledge-graph-spine.ps1`
   - Deletes the curated `display_spine_v0.1` rows from MySQL, restarts the backend, waits for startup seeding to finish, and verifies non-zero node and edge counts.
