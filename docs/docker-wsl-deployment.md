# WSL Docker deployment

The compose project packages the frontend/Nginx, Java backend, Python model worker, MySQL, Redis, RabbitMQ and a
standalone Milvus stack (Milvus, etcd and MinIO). Its default host ports deliberately avoid the Windows development
services on `5173`, `8080`, `8091`, `3306`, `6379`, `19530` and `9091`.

## Configure

Run from the repository root in WSL. Keep `.env` local and replace every required placeholder; Compose rejects a
missing database, worker, MinIO or Milvus secret before it creates a container.

```bash
cp .env.example .env
chmod 600 .env
# Edit .env with deployment-owned values. Do not commit it.
docker compose --env-file .env config -q
```

For real generation set `OPENAI_API_KEY`, `OPENAI_BASE_URL` and `OPENAI_CHAT_MODEL`. For Feishu app/bot sync set
`FEISHU_APP_ID` and `FEISHU_APP_SECRET`; user OAuth additionally requires the two redirect URLs and the token
encryption key. The backend image contains the project downloader at `/app/scripts/download_feishu_url.py`.

Local BGE/CLIP weights are not baked into an image. Set `MATH_AGENT_MODEL_ROOT` to a host directory and set the three
`MATH_AGENT_CONTAINER_*_MODEL_PATH` values to matching paths below the container's read-only `/models` mount.

## Build and start

```bash
docker compose --env-file .env build
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

MySQL initializes all SQL files under `backend-java/src/main/resources/db` only when its named volume is empty.
Existing named volumes are never reformatted by `up` or `down`.

## Verify

```bash
curl --fail http://127.0.0.1:5174/healthz
curl --fail http://127.0.0.1:5174/api/system/health
curl --fail http://127.0.0.1:8081/api/system/health
curl --fail http://127.0.0.1:8092/health
docker compose --env-file .env ps
```

The browser entry point is `http://127.0.0.1:5174/`; Nginx proxies `/api/*` to the backend so sessions, SSE, resource
assets and MCP use the same origin. External MCP clients use `http://127.0.0.1:8081/api/mcp` or the proxied
`http://127.0.0.1:5174/api/mcp`.

Stop without deleting durable data:

```bash
docker compose --env-file .env down
```

Do not add `-v` unless the named MySQL, Redis, RabbitMQ, MinIO, etcd, Milvus, worker and backend volumes are all
intentionally disposable and have been backed up.
