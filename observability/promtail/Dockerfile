FROM busybox:1.37.0-musl AS healthcheck

# Promtail este EOL. Versiunea fixata este folosita pentru cerinta hackathonului.
FROM grafana/promtail:3.6.11
COPY --from=healthcheck /bin/busybox /bin/busybox
USER 10001:10001
