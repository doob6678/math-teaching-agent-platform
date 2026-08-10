# 2026-08-06 WSL/Linux Dependency Audit

## Scope and safety boundary

This is a read-only preflight audit for the handout Python production-convergence plan. It inspected only this repository's dependency manifests, Compose configuration, running WSL/Docker services, model mounts, and health endpoints. No project code, database schema, DNS, IP address, container, volume, or service state was changed. No package was installed because all required project dependencies were already available in the running images or are not required by project source code.

Secrets were never printed. Environment values below are recorded only as configured/missing.

## Relevant project contracts read

- `docs/superpowers/plans/2026-08-05-handout-python-production-convergence.md`
- `docker-compose.yml`
- `docs/docker-wsl-deployment.md`
- `ai-worker-python/Dockerfile`
- `ai-worker-python/requirements.txt`
- `ai-worker-python/requirements-runtime.txt`
- `ai-worker-python/requirements-clip.txt`

The plan requires the real WSL Compose stack, MySQL, Redis, RabbitMQ, Milvus, CUDA, mounted local model weights, and forbids a CPU local-model fallback. The Compose file requests `gpus: all` for `ai-worker`; its model-device defaults are `cuda`.

## Execution summary

| Category | Calls | Passed | Failed / incomplete | Elapsed |
| --- | ---: | ---: | ---: | ---: |
| Initial WSL/tool and Compose probes | 3 | 1 | 2 command-construction failures, no environment change | 57.84 s |
| Corrected WSL/tool, Compose, and configuration probes | 3 | 3 | 0 | 61.71 s |
| HTTP and service-health probes | 2 | 2 | 0 at the observed successful endpoint sample | 65.00 s |
| Container service probes | 2 | 2 | 0 after authenticated Redis retry | 37.83 s |
| AI worker, model mount, capacity, and source checks | 8 | 7 | 1 optional `redis` import probe; project source does not import it | 119.24 s |
| **Total recorded audit command time** | **18** | **15** | **3** | **341.62 s** |

The two initial WSL failures resulted from PowerShell expanding Bash variables before WSL received them. The commands did not write files or mutate services. The retry passed scripts through Base64 and produced the results reported below.

No provider inference call was made during this dependency audit, so provider success/failure counts and token usage are both `0`; no token or cost figure has been fabricated.

## Host and WSL runtime

| Item | Result |
| --- | --- |
| WSL distribution | Ubuntu 24.04 LTS on WSL2 |
| WSL kernel | Linux 6.6.114.1-microsoft-standard-WSL2 x86_64 |
| Docker Engine | 29.5.1 |
| Python | 3.12.3 with pip 26.1.1 |
| Java | OpenJDK 21.0.11 |
| Maven | 3.9.11 |
| npm | 10.9.8; `node` binary is not separately discoverable in the WSL PATH |
| Git / curl | 2.43.0 / 8.5.0 |
| MySQL / Redis CLIs | MySQL client 8.0.46 / redis-cli 7.0.15 |
| GPU | NVIDIA GeForce RTX 5060 Laptop GPU, driver 595.71.01 (host query also reported 596.36) |
| GPU memory at sample | 8151 MiB total, 0% compute and memory utilisation |
| WSL memory at sample | 15 GiB total, 12 GiB available, swap unused |
| Disk capacity | WSL root: 840 GiB free; Windows mount: 234 GiB free |
| Docker cache capacity | 37 images / 75.14 GB; build cache 68.07 GB with 40.01 GB reclaimable; no cleanup was performed |

The WSL invocation emitted the existing warning about a localhost proxy not being mirrored into WSL NAT mode. This audit did not change proxy, DNS, IP, or networking settings.

## Environment and mounted models

Configuration presence in `.env` was checked without revealing values:

| Key | Status |
| --- | --- |
| `OPENAI_API_KEY` | configured |
| `MYSQL_ROOT_PASSWORD` | configured |
| `MATH_AGENT_AI_RUNTIME_DB_PASSWORD` | configured |
| `MATH_AGENT_MODEL_ROOT` | configured |
| `MATH_AGENT_CONTAINER_CLIP_MODEL_PATH` | configured |
| `MATH_AGENT_CONTAINER_TEXT_MODEL_PATH` | Compose default applies |
| `MATH_AGENT_CONTAINER_RERANK_MODEL_PATH` | Compose default applies |

The live `ai-worker` container confirms that all three mounted local model directories exist:

- `bge-small-zh-v1.5`: present
- `bge-reranker-v2-m3`: present
- `multi-modal_clip-vit-large-patch14_zh`: present

Its Docker device request is a GPU request with `Count=-1` (`gpus: all`). A direct runtime probe succeeded:

```text
torch=2.13.0+cu130
cuda_available=True
cuda_device_count=1
cuda_device=NVIDIA GeForce RTX 5060 Laptop GPU
```

This confirms the available execution environment is CUDA-capable and no CPU local-model fallback was used in the audit.

## Dependency result

The `ai-worker` image passes `python -m pip check` with `No broken requirements found`.

Direct imports used by the running Python worker succeeded for `torch`, `fastapi`, `langgraph`, `pydantic`, and `pymysql`. An additional direct `import redis` probe failed with `ModuleNotFoundError`, but `redis` is absent from all three requirement manifests and `rg` found no production Python import of it. It is therefore not a project dependency gap and was deliberately not installed.

`frontend/node_modules` is present. The source tree does not contain an executable `backend-java/mvnw`; Maven 3.9.11 is installed in WSL and the production backend is already built and running in Compose.

## Compose and service verification

`docker compose --env-file .env config -q` passed. The following images/services existed during the audit:

| Service | Image | Verified state |
| --- | --- | --- |
| MySQL | `mysql:8.4` | authenticated `mysqladmin ping` passed |
| Redis | `redis:7.4-alpine` | authenticated `redis-cli ping` returned `PONG` |
| RabbitMQ | `rabbitmq:4-management` | `rabbitmq-diagnostics -q ping` passed |
| Milvus | `milvusdb/milvus:v2.6.11` | reached healthy state during the audit |
| AI worker | `math-agent-rag-ai-worker` | Compose health endpoint returned HTTP 200 |
| Backend | `math-agent-rag-backend` | `/api/system/health` returned HTTP 200 in 6.882 s |
| Frontend | `math-agent-rag-frontend` | `/healthz` returned HTTP 200 in 0.002 s |

The direct endpoint sample was:

```text
http://127.0.0.1:5173/healthz            HTTP 200, 0.001612 s
http://127.0.0.1:8080/api/system/health  HTTP 200, 6.882374 s
http://127.0.0.1:8092/health             HTTP 200, 0.005885 s
http://127.0.0.1:9092/healthz            HTTP 200, 0.001325 s
```

Container health was observed while another process was evidently bringing the Compose stack up. MySQL, Redis, RabbitMQ, AI worker, and frontend reached `healthy`; backend and Milvus alternated between `starting` and `healthy` during their startup window. At one intermediate Milvus probe its internal endpoint returned `500 Not all components are healthy, 2/5`, with transient gRPC connection-refused messages to stale coordinator/session addresses. A later state sample reported Milvus healthy. The backend endpoint remained HTTP 200 even while its Compose health state was still `starting`.

This means an acceptance run must take and preserve its own final `docker compose ps` and health artifacts after the stack stabilises. Do not use the earlier transient `starting` samples as acceptance evidence.

## Commands run

All WSL commands were invoked through `wsl.exe -d Ubuntu -- bash -lc` from the repository root. Representative commands, with secret values excluded, were:

```bash
docker compose --env-file .env config -q
docker compose --env-file .env ps
docker compose --env-file .env exec -T mysql sh -lc 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
docker compose --env-file .env exec -T redis sh -lc 'redis-cli -a "$REDIS_PASSWORD" ping'
docker compose --env-file .env exec -T rabbitmq rabbitmq-diagnostics -q ping
curl --noproxy '*' --connect-timeout 4 --max-time 12 http://127.0.0.1:8092/health
docker exec -i=false <ai-worker-container> python -m pip check
docker exec -i=false <ai-worker-container> python -c '<CUDA availability and device query>'
docker stats --no-stream
nvidia-smi --query-gpu=name,driver_version,memory.total,utilization.gpu,utilization.memory --format=csv,noheader
free -h && vmstat 1 2
```

## Conclusion

No installation is required or justified before continuing implementation. The prerequisite stack, required secrets, CUDA runtime, local model mounts, Docker images, frontend dependencies, and core database/message services are already present. The only operational caution is to wait for Milvus and backend Compose health to remain stable, then capture a fresh final health snapshot before a real Luna acceptance run. This report contains no provider or handout test claim; it is dependency-preflight evidence only.
