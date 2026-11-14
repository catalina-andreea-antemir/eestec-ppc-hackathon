# syntax=docker/dockerfile:1
# Storage FTP (C6): vsftpd pe Alpine, mod pasiv. Porturile pasive (21000-21049) exista doar in reteaua interna
# storage-net; nu se publica nimic. Utilizatorul si parola vin din .env (FTP_USER, FTP_PASSWORD), la pornire.
FROM alpine:3.24.4

RUN apk add --no-cache vsftpd \
    && mkdir -p /srv/ftp /var/run/vsftpd/empty

COPY vsftpd.conf /etc/vsftpd/vsftpd.conf
COPY --chmod=755 entrypoint.sh /usr/local/bin/ftp-entrypoint.sh
COPY --chmod=755 healthcheck.sh /usr/local/bin/ftp-healthcheck.sh

EXPOSE 21 21000-21049
VOLUME ["/srv/ftp"]
ENTRYPOINT ["/usr/local/bin/ftp-entrypoint.sh"]
