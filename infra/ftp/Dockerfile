# syntax=docker/dockerfile:1
# FTP storage (C6): vsftpd on Alpine, passive mode. Passive ports (21000-21049) exist only on the internal
# storage-net; nothing is published. User and password come from .env (FTP_USER, FTP_PASSWORD) at startup.
FROM alpine:3.24.4

RUN apk add --no-cache vsftpd \
    && mkdir -p /srv/ftp /var/run/vsftpd/empty

COPY vsftpd.conf /etc/vsftpd/vsftpd.conf
COPY --chmod=755 entrypoint.sh /usr/local/bin/ftp-entrypoint.sh
COPY --chmod=755 healthcheck.sh /usr/local/bin/ftp-healthcheck.sh

EXPOSE 21 21000-21049
VOLUME ["/srv/ftp"]
ENTRYPOINT ["/usr/local/bin/ftp-entrypoint.sh"]
