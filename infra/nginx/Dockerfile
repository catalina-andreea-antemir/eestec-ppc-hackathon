# syntax=docker/dockerfile:1
# nginx (C3): builds the Angular UI (frontend/filegrid-ui) and serves it statically, with TLS and a proxy to api.
# The build context is the REPO ROOT:  docker build -f infra/nginx/Dockerfile .
# The context is filtered by Dockerfile.dockerignore (next to this file).

FROM node:24.22.3-alpine3.24 AS ui
WORKDIR /ui
ENV NG_CLI_ANALYTICS=false CI=true
COPY frontend/filegrid-ui/package.json frontend/filegrid-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/filegrid-ui/ ./
# `ng build` defaults to production (environment.ts: useMocks false, apiBaseUrl /api/v1).
RUN npx ng build --configuration production

FROM nginx:1.30.6-alpine
# openssl is only needed for the self-signed certificate generated on first start (kept in the nginx-certs volume).
RUN apk add --no-cache openssl \
    && rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /etc/nginx/certs
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY infra/nginx/conf.d/ /etc/nginx/conf.d/
COPY --chmod=755 infra/nginx/10-self-signed-cert.sh /docker-entrypoint.d/10-self-signed-cert.sh
COPY --from=ui /ui/dist/filegrid-ui/browser/ /usr/share/nginx/html/
EXPOSE 80 443
