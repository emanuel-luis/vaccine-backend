#!/usr/bin/env bash
# End-to-end check of the running API against the dbt-built marts: every endpoint must answer 200
# with at least one row, and a call without a token must answer 401. Tokens come from Keycloak
# (client vaccine-smoke, secret from .env, never printed).
# Usage: scripts/smoke.sh [base_url]   (KEYCLOAK_URL overrides http://localhost:8180)
set -euo pipefail
base="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib-token.sh
. scripts/lib-token.sh
load_env

paths=(
  "/actuator/health"
  "/api/v1/me"
  "/api/v1/doses/by-state-month?from=2024-01&to=2025-12&state=SP"
  "/api/v1/doses/by-age-band?from=2024-01&to=2024-12&state=MA&vaccine=PENTA"
  "/api/v1/coverage?year=2024&state=SP"
  "/api/v1/health-units/0000264/history"
  "/api/v1/runs/latest"
)

status="$(curl -sS -o /dev/null -w '%{http_code}' "$base/api/v1/coverage")"
if [[ "$status" != 401 ]]; then
  echo "FAIL /api/v1/coverage without a token answered $status, expected 401" >&2
  exit 1
fi
echo "ok   /api/v1/coverage without a token -> 401"

access="$(token vaccine-smoke "${SMOKE_CLIENT_SECRET:-}")"
for path in "${paths[@]}"; do
  if [[ "$path" == /actuator/* ]]; then
    body="$(curl -fsS "$base$path")"
  else
    body="$(curl -fsS -H "Authorization: Bearer $access" "$base$path")"
    if ! grep -Eq '"count":[1-9]' <<<"$body"; then
      echo "FAIL $path returned no rows: ${body:0:200}" >&2
      exit 1
    fi
  fi
  echo "ok   $path  ${body:0:120}"
done
