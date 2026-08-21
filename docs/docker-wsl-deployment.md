# WSL Docker deployment

The compose project packages the frontend/Nginx, Java backend, Python model worker, MySQL, Redis, RabbitMQ and the
compose-owned Milvus stack (Milvus, etcd and MinIO). The active host ports are frontend `5173`, backend `8080`,
worker `8092`, MySQL `3307`, Redis `6380`, RabbitMQ `5673/15673`, and Milvus `19531`. Port `19530` belongs to a
separate legacy `milvus-standalone` container and must not be used for this project.

## Configure

Run from the repository root in WSL. Keep `.env` local and replace every required placeholder. The canonical local
provider is `https://api1.aisz.mom/v1` with model `gpt-5.6-luna`; both are explicit defaults and can be overridden
only by `OPENAI_BASE_URL`/`OPENAI_CHAT_MODEL` in `.env`. The host paths in `.env` are required read-only mounts:
`MATH_AGENT_MODEL_ROOT`, `MATH_AGENT_PROCESSED_BOOKS_HOST_ROOT`, `MATH_AGENT_PDF_FONT_HOST_PATH`, and
`MATH_AGENT_GAOKAO_INPUT_HOST_ROOT`. `MATH_AGENT_LOCAL_TEACHER_RESOURCES_HOST_ROOT` is an optional backend-only,
read-only teacher corpus mount; its stable in-container destination is configured by
`MATH_AGENT_LOCAL_TEACHER_RESOURCES_ROOT`.

```bash
cp .env.example .env
chmod 600 .env
# Edit .env with deployment-owned values. Do not commit it.
bash scripts/local/preflight-wsl.sh .env
docker compose --env-file .env config -q
```

For real generation set `OPENAI_API_KEY` (the endpoint/model above are already configured). For Feishu app/bot sync set
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

Teacher source durability is intentionally more specific than the generic backend volume. Browser uploads persist in
`./.local-storage/teacher-resource-uploads`, downloaded source files and their catalog persist in
`./.local-storage/teacher-source-imports`, and extracted assets persist in `./.local-storage/teacher-assets`. Do not
delete or replace one of these directories independently: the source catalog, MySQL source metadata and the actual
source tree must remain available at the same container paths for authorized handout document reads.

## Verify

```bash
curl --fail http://127.0.0.1:5173/healthz
curl --fail http://127.0.0.1:8080/api/system/health
curl --fail http://127.0.0.1:8092/health
docker compose --env-file .env ps
```

The browser entry point is `http://127.0.0.1:5173/`; Nginx proxies `/api/*` to the backend so sessions, SSE, resource
assets and MCP use the same origin. Direct backend clients use `http://127.0.0.1:8080/api/*`.

Stop without deleting durable data:

```bash
docker compose --env-file .env down
```

Do not add `-v` unless the named MySQL, Redis, RabbitMQ, MinIO, etcd, Milvus, worker and backend volumes are all
intentionally disposable and have been backed up.
