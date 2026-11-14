# syntax=docker/dockerfile:1
# nginx (C3): construieste interfata Angular (frontend/filegrid-ui, F2) si o serveste static, cu TLS si proxy catre api.
# Contextul de build este RADACINA repo-ului:  docker build -f infra/nginx/Dockerfile .
# Ce intra in context e filtrat de Dockerfile.dockerignore (langa acest fisier).

FROM node:24.22.3-alpine3.24 AS ui
WORKDIR /ui
ENV NG_CLI_ANALYTICS=false CI=true
COPY frontend/filegrid-ui/package.json frontend/filegrid-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/filegrid-ui/ ./
# Configuratia implicita a lui `ng build` este production (environment.ts: useMocks false, apiBaseUrl /api/v1).
RUN npx ng build --configuration production

FROM nginx:1.30.6-alpine
# openssl doar pentru certificatul self-signed generat la prima pornire (nu se comite, sta in volumul nginx-certs).
RUN apk add --no-cache openssl \
    && rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /etc/nginx/certs
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY infra/nginx/conf.d/ /etc/nginx/conf.d/
COPY --chmod=755 infra/nginx/10-self-signed-cert.sh /docker-entrypoint.d/10-self-signed-cert.sh
COPY --from=ui /ui/dist/filegrid-ui/browser/ /usr/share/nginx/html/
EXPOSE 80 443
