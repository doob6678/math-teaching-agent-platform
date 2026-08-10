#!/usr/bin/env bash
set -Eeuo pipefail

# This collector is intentionally read-only. It records the deployment inputs needed to interpret a handout run;
# it never changes DNS/IP configuration, starts a service, edits a database, or installs a package.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${HANDOUT_BASELINE_OUTPUT_DIR:-${ROOT_DIR}/output/acceptance/baseline-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "${OUTPUT_DIR}"

run_capture() {
  local name="$1"
  shift
  if "$@" >"${OUTPUT_DIR}/${name}.txt" 2>&1; then
    printf '%s\t%s\n' "${name}" "ok" >>"${OUTPUT_DIR}/commands.tsv"
  else
    printf '%s\t%s\n' "${name}" "failed:$?" >>"${OUTPUT_DIR}/commands.tsv"
  fi
}

cd "${ROOT_DIR}"
: >"${OUTPUT_DIR}/commands.tsv"
printf 'capturedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${OUTPUT_DIR}/metadata.txt"
git rev-parse HEAD >>"${OUTPUT_DIR}/metadata.txt" 2>&1 || true

run_capture compose-ps docker compose --env-file .env ps
run_capture compose-config docker compose --env-file .env config -q
run_capture nvidia-smi nvidia-smi --query-gpu=name,driver_version,utilization.gpu,utilization.memory,memory.used,memory.total --format=csv,noheader,nounits
run_capture free free -h
run_capture vmstat vmstat 1 2
run_capture docker-stats docker stats --no-stream

for endpoint in \
  "http://127.0.0.1:8080/api/system/health" \
  "http://127.0.0.1:8092/health" \
  "http://127.0.0.1:9092/healthz" \
  "http://127.0.0.1:5173/healthz"; do
  safe_name="health-$(printf '%s' "${endpoint}" | tr '/:' '__')"
  run_capture "${safe_name}" curl --noproxy '*' --connect-timeout 4 --max-time 20 --silent --show-error --fail "${endpoint}"
done

# Hash every captured artifact after collection so reports can be audited without trusting mutable timestamps.
(
  cd "${OUTPUT_DIR}"
  find . -maxdepth 1 -type f ! -name manifest.sha256 -print0 | sort -z | xargs -0 sha256sum >manifest.sha256
)
printf 'output=%s\n' "${OUTPUT_DIR}"
