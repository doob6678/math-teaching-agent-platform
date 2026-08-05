#!/usr/bin/env bash
# Capture host and Compose evidence for an already completed real handout run.
# This script is intentionally read-only: it neither starts services nor changes DNS,
# IP addresses, credentials, queues, or provider configuration.
set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly DEFAULT_RUN_DIRECTORY="${PROJECT_ROOT}/output/acceptance/python-langgraph-handout/run-real-luna-20260804-final-v6"
readonly RUN_DIRECTORY="${HANDOUT_BASELINE_RUN_DIRECTORY:-${DEFAULT_RUN_DIRECTORY}}"
readonly OUTPUT_DIRECTORY="${HANDOUT_BASELINE_OUTPUT_DIRECTORY:-${PROJECT_ROOT}/output/acceptance/2026-08-05-handout-baseline}"
readonly COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"

if [[ ! -d "${RUN_DIRECTORY}" ]]; then
  printf 'Real handout run directory does not exist: %s\n' "${RUN_DIRECTORY}" >&2
  exit 2
fi

mkdir -p "${OUTPUT_DIRECTORY}"

# All snapshots are kept independently so a later report can expose which source was unavailable.
git -C "${PROJECT_ROOT}" rev-parse HEAD > "${OUTPUT_DIRECTORY}/git-sha.txt"
docker compose -f "${COMPOSE_FILE}" ps --format json > "${OUTPUT_DIRECTORY}/compose-ps.json"
docker compose -f "${COMPOSE_FILE}" ps > "${OUTPUT_DIRECTORY}/compose-ps.txt"
docker compose -f "${COMPOSE_FILE}" config --images > "${OUTPUT_DIRECTORY}/compose-images.txt"
free -h > "${OUTPUT_DIRECTORY}/free-h.txt"
vmstat 1 3 > "${OUTPUT_DIRECTORY}/vmstat.txt"
uptime > "${OUTPUT_DIRECTORY}/uptime.txt"

if command -v nvidia-smi >/dev/null 2>&1; then
  nvidia-smi --query-gpu=name,driver_version,memory.total,memory.used,utilization.gpu,utilization.memory \
    --format=csv,noheader > "${OUTPUT_DIRECTORY}/nvidia-smi.csv" || true
else
  printf 'nvidia-smi is unavailable\n' > "${OUTPUT_DIRECTORY}/nvidia-smi.csv"
fi

# Docker health states cover MySQL, Redis, RabbitMQ and Milvus without reading passwords from .env.
for service in mysql redis rabbitmq milvus ai-worker backend; do
  docker compose -f "${COMPOSE_FILE}" ps --format json "${service}" \
    > "${OUTPUT_DIRECTORY}/health-${service}.json"
done

(
  cd "${RUN_DIRECTORY}"
  find . -type f -print0 | sort -z | xargs -0 -r sha256sum
) > "${OUTPUT_DIRECTORY}/run-sha256-manifest.txt"

python3 "${PROJECT_ROOT}/scripts/local/summarize-handout-runs.py" "${RUN_DIRECTORY}" --json \
  > "${OUTPUT_DIRECTORY}/run-summary.json"

printf 'Captured baseline evidence in %s\n' "${OUTPUT_DIRECTORY}"
