#!/usr/bin/env bash
# Provision the MySQL account used only by the Python handout runtime.
# Run this after Java/Flyway has created V32 and before enabling the Python handout flag.
set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"

# Values are resolved inside the existing MySQL container, so this script never prints root or runtime passwords.
docker compose -f "${COMPOSE_FILE}" exec -T mysql sh -ceu '
  : "${MYSQL_ROOT_PASSWORD:?MySQL root password is required}"
  : "${MATH_AGENT_DB_NAME:?Runtime database name is required}"
  : "${MATH_AGENT_AI_RUNTIME_DB_USERNAME:?Runtime database user is required}"
  : "${MATH_AGENT_AI_RUNTIME_DB_PASSWORD:?Runtime database password is required}"

  case "${MATH_AGENT_DB_NAME}" in (*[!A-Za-z0-9_]*|"") exit 2;; esac
  case "${MATH_AGENT_AI_RUNTIME_DB_USERNAME}" in (*[!A-Za-z0-9_]*|"") exit 2;; esac
  # The deployment password is generated as an alphanumeric secret, making the SQL literal safe without logging it.
  case "${MATH_AGENT_AI_RUNTIME_DB_PASSWORD}" in (*[!A-Za-z0-9]*|????????????????????????????????*) ;; (*) exit 2;; esac

  mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS '\''${MATH_AGENT_AI_RUNTIME_DB_USERNAME}'\''@'\''%'\'' IDENTIFIED BY '\''${MATH_AGENT_AI_RUNTIME_DB_PASSWORD}'\'';
ALTER USER '\''${MATH_AGENT_AI_RUNTIME_DB_USERNAME}'\''@'\''%'\'' IDENTIFIED BY '\''${MATH_AGENT_AI_RUNTIME_DB_PASSWORD}'\'';
GRANT SELECT, INSERT, UPDATE ON \`${MATH_AGENT_DB_NAME}\`.handout_checkpoint TO '\''${MATH_AGENT_AI_RUNTIME_DB_USERNAME}'\''@'\''%'\'';
GRANT SELECT, INSERT, UPDATE ON \`${MATH_AGENT_DB_NAME}\`.handout_event TO '\''${MATH_AGENT_AI_RUNTIME_DB_USERNAME}'\''@'\''%'\'';
GRANT SELECT, INSERT, UPDATE ON \`${MATH_AGENT_DB_NAME}\`.ai_usage_event TO '\''${MATH_AGENT_AI_RUNTIME_DB_USERNAME}'\''@'\''%'\'';
FLUSH PRIVILEGES;
SQL
'

printf 'Provisioned restricted AI runtime database account.\n'
