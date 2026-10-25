#!/usr/bin/env bash
# End-to-end check of the running API against the dbt-built marts: every endpoint must answer 200
# with at least one row. Usage: scripts/smoke.sh [base_url]
set -euo pipefail
base="${1:-http://localhost:8080}"
paths=(
  "/actuator/health"
  "/api/v1/doses/by-state-month?from=2024-01&to=2025-12&state=SP"
  "/api/v1/doses/by-age-band?from=2024-01&to=2024-12&state=MA&vaccine=PENTA"
  "/api/v1/coverage?year=2024&state=SP"
  "/api/v1/health-units/0000264/history"
  "/api/v1/runs/latest"
)
for path in "${paths[@]}"; do
  body="$(curl -fsS "$base$path")"
  if [[ "$path" != /actuator/* ]] && ! grep -Eq '"count":[1-9]' <<<"$body"; then
    echo "FAIL $path returned no rows: ${body:0:200}" >&2
    exit 1
  fi
  echo "ok   $path  ${body:0:120}"
done
