FROM busybox:1.37.0-musl AS healthcheck

# Promtail is EOL. This pinned version is used because the hackathon requires it.
FROM grafana/promtail:3.6.11
COPY --from=healthcheck /bin/busybox /bin/busybox
USER 10001:10001
