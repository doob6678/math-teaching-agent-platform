# Local startup scripts

These scripts start only real services. They do not switch the backend into no-database mode.

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
   - Registers one real local teacher resource through the backend capability-token flow.
   - By default stages one existing PDF from the workspace into `.local-storage/seed-resources/` using an ASCII path, then creates and executes a sync job.
   - Uses only backend APIs; it does not write `source_document`, `document_block`, or Milvus directly.

10. `archive-teacher-resource.ps1`
   - Archives one teacher resource through the backend capability-token flow.
   - The backend now deletes that document's vectors from Milvus during archive, preventing archived resources from polluting future search.

11. `reseed-knowledge-graph-spine.ps1`
   - Deletes the curated `display_spine_v0.1` rows from MySQL, restarts the backend, waits for startup seeding to finish, and verifies non-zero node and edge counts.
