#!/usr/bin/env bash
# Sourced by smoke.sh and speed-check.sh (not run on its own). Client-credentials tokens from the
# local Keycloak. Secrets come from .env and are sent on stdin, never printed or put in argv.

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"

# load_env: export the variables of .env (run from the repo root).
load_env() {
  if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
  fi
}

# token <client_id> <secret>: prints an access token. Secrets here are plain [A-Za-z0-9_-] values.
token() {
  local client="$1" secret="$2" response access
  [[ -n "$secret" ]] || { echo "FAIL no secret for client $client (set it in .env)" >&2; return 1; }
  response="$(printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' "$client" "$secret" |
    curl -fsS -X POST -H 'Content-Type: application/x-www-form-urlencoded' -d @- \
      "$KEYCLOAK_URL/realms/vaccine/protocol/openid-connect/token")" || {
    echo "FAIL token request for $client at $KEYCLOAK_URL (Keycloak up? realm imported? secret in .env matches the one it was started with?)" >&2
    return 1
  }
  access="$(sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' <<<"$response")"
  [[ -n "$access" ]] || { echo "FAIL no access_token in the response for $client" >&2; return 1; }
  printf '%s' "$access"
}
