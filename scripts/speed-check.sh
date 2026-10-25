#!/usr/bin/env bash
# Speed-layer check against the running stack (run `make demo` first): posts 3 doses (token of
# vaccine-simulator), requires "doses today" to grow by 3 (token of vaccine-smoke), replays one
# POST, then runs `make dbt-build` and `make reconcile` and requires the total to stay the same with
# nothing pending. Secrets come from .env and are never printed.
# Usage: scripts/speed-check.sh [base_url]   (KEYCLOAK_URL overrides http://localhost:8180)
set -euo pipefail
base="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
# shellcheck source=scripts/lib-token.sh
. scripts/lib-token.sh
load_env
reader="$(token vaccine-smoke "${SMOKE_CLIENT_SECRET:-}")"
writer="$(token vaccine-simulator "${SIMULATOR_CLIENT_SECRET:-}")"

# psql inside the postgres container; credentials come from the container's own environment.
psql_q() {
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -A -t -q -F "|"'
}

# jget <dotted.path>: reads JSON on stdin.
jget() {
  if command -v jq >/dev/null; then
    jq -r ".$1"
  else
    python3 -c 'import json, sys
v = json.load(sys.stdin)
for k in sys.argv[1].split("."):
    v = v[k]
print(v)' "$1"
  fi
}

fail() {
  echo "FAIL $*" >&2
  exit 1
}

# An active unit in the version valid today, and a first dose whose recommended age is inside its
# window (so the age at the dose is valid). Birth date = today minus the recommended age.
IFS='|' read -r today cnes vaccine dose birth < <(psql_q <<'SQL'
with today as (select timezone('America/Sao_Paulo', now())::date as d)
select t.d, u.cnes_code, v.vaccine_code, v.dose_label, t.d - v.recommended_age_days
from today t
cross join marts.dim_health_unit u
cross join marts.dim_vaccine v
where u.is_active and u.cnes_code <> '0000000' and u.municipality_code <> 0
  and u.valid_from <= t.d and t.d < u.valid_to
  and v.dose_number = 1 and v.vaccine_code <> 'UNKNOWN'
  and v.min_age_days <= v.recommended_age_days and v.recommended_age_days <= v.max_age_days
  and v.recommended_age_days < 6 * 365
order by u.cnes_code, v.vaccine_code
limit 1;
SQL
)
[[ -n "${cnes:-}" ]] || fail "no active unit or schedule dose found in the marts (run make demo first)"
echo "unit $cnes, $vaccine $dose, birth date $birth, today $today"

mapfile -t persons < <(psql_q <<<'select gen_random_uuid() from generate_series(1, 3);')

summary() { curl -fsS -H "Authorization: Bearer $reader" "$base/api/v1/dashboard/summary?from=$today&to=$today"; }
today_doses() { summary | jget data.dosesToday; }

before="$(today_doses)"
run="$(date +%s)"
declare -a keys bodies
for i in 0 1 2; do
  keys[i]="speed-check-$run-$i"
  bodies[i]="$(printf '{"personId":"%s","birthDate":"%s","sex":"F","cnesCode":"%s","vaccineCode":"%s","doseLabel":"%s","administeredAt":"%s"}' \
    "${persons[i]}" "$birth" "$cnes" "$vaccine" "$dose" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"
  status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/api/v1/doses" \
    -H "Authorization: Bearer $writer" -H 'Content-Type: application/json' -H "Idempotency-Key: ${keys[i]}" -d "${bodies[i]}")"
  [[ "$status" == 201 ]] || fail "POST /api/v1/doses #$((i + 1)) answered $status, expected 201"
done
echo "ok   3 doses accepted (201)"

after="$(today_doses)"
[[ "$after" == $((before + 3)) ]] || fail "dosesToday went $before -> $after, expected +3"
pending="$(summary | jget data.liveDosesPending)"
(( pending >= 3 )) || fail "liveDosesPending is $pending before the batch, expected at least 3"
echo "ok   dosesToday $before -> $after, liveDosesPending $pending"

status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/api/v1/doses" \
  -H "Authorization: Bearer $writer" -H 'Content-Type: application/json' -H "Idempotency-Key: ${keys[0]}" -d "${bodies[0]}")"
[[ "$status" == 200 ]] || fail "replayed POST answered $status, expected 200"
[[ "$(today_doses)" == "$after" ]] || fail "a replayed POST changed dosesToday"
echo "ok   replay answered 200 without a change"

make dbt-build
make reconcile

final="$(today_doses)"
[[ "$final" == "$after" ]] || fail "dosesToday changed across the batch: $after -> $final"
pending="$(summary | jget data.liveDosesPending)"
[[ "$pending" == 0 ]] || fail "liveDosesPending is $pending after the batch, expected 0"
echo "ok   dosesToday still $final after dbt build, liveDosesPending 0"
