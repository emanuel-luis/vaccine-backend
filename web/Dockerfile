# Build context is the repository root: docker build -f web/Dockerfile .
FROM node:24.22.1-alpine AS build
WORKDIR /app/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/web/dist /usr/share/nginx/html
# Dev defaults; compose overrides them. Only these prefixes go through envsubst.
ENV NGINX_ENVSUBST_FILTER="^(API_|OIDC_)" \
    API_UPSTREAM="http://api:8080" \
    API_BASE_URL="" \
    OIDC_AUTHORITY="http://localhost:8180/realms/vaccine" \
    OIDC_ORIGIN="http://localhost:8180" \
    OIDC_CLIENT_ID="vaccine-web"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://localhost:8080/healthz || exit 1
