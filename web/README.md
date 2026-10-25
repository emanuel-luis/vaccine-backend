# web

Vite + React + TypeScript SPA for the vaccination dashboards. Node 24 (`.nvmrc`).

## Commands

```
npm ci            # or npm install
npm run dev       # http://localhost:5173, proxies /api to http://localhost:8080
npm run lint
npm run typecheck
npm test
npm run build
npm run gen:api   # regenerate src/api/schema.ts from ../docs/api/openapi.json
```

`src/api/schema.ts` is a temporary hand-written subset until `docs/api/openapi.json` lands;
`npm run gen:api` replaces it.

## Runtime config

The app fetches `/config.json` before rendering:

```json
{ "apiBaseUrl": "", "oidc": { "authority": "http://localhost:8180/realms/vaccine", "clientId": "vaccine-web" } }
```

`public/config.json` holds the dev defaults. In the container, nginx renders it from the env vars
`API_BASE_URL`, `OIDC_AUTHORITY`, `OIDC_CLIENT_ID`, and also uses `API_UPSTREAM` (proxy target for
`/api/`) and `OIDC_ORIGIN` (CSP `connect-src`). The Docker build context is the repo root:
`docker build -f web/Dockerfile .`.

## Local auth

Login is Keycloak authorization code with PKCE (client `vaccine-web`, realm `vaccine`, issuer
`http://localhost:8180`). Roles come from the access-token claim `roles` and the region scope from
`region_scope` (`BR`, `UF:XX`, `MUN:1234567`); the browser decodes them for UI gating only, the API
enforces access. Every route except `/sobre` requires login.
