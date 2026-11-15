#!/bin/sh
# Creates (or updates) the FTP user from the environment and runs vsftpd in the foreground.
set -eu

: "${FTP_USER:?Lipseste FTP_USER}"
: "${FTP_PASSWORD:?Lipseste FTP_PASSWORD}"

case "$FTP_USER" in
    *[!a-z0-9_-]* | "" | -*)
        echo "FTP_USER may only contain lowercase letters, digits, '_' and '-'." >&2
        exit 1
        ;;
esac

if ! id "$FTP_USER" >/dev/null 2>&1; then
    adduser -D -H -h /srv/ftp -s /sbin/nologin -u 1000 "$FTP_USER"
fi
# chpasswd reads from stdin, so the password never shows up in the process list.
printf '%s:%s\n' "$FTP_USER" "$FTP_PASSWORD" | chpasswd >/dev/null 2>&1
printf '%s\n' "$FTP_USER" >/etc/vsftpd/user_list

# vsftpd requires the user's shell to be listed in /etc/shells.
grep -qx /sbin/nologin /etc/shells || echo /sbin/nologin >>/etc/shells

chown "$FTP_USER:$FTP_USER" /srv/ftp
chmod 755 /srv/ftp

# Development only: FTP_PASV_ADDRESS (set in docker-compose.dev.yml) makes vsftpd advertise this address in the
# PASV reply, so the server is usable from the host through the ports published on 127.0.0.1. Without the
# variable nothing changes and the image's vsftpd.conf is used as is.
CONF=/etc/vsftpd/vsftpd.conf
if [ -n "${FTP_PASV_ADDRESS:-}" ]; then
    case "$FTP_PASV_ADDRESS" in
        *[!0-9.]* | "" | .* | *.)
            echo "FTP_PASV_ADDRESS must be an IPv4 address." >&2
            exit 1
            ;;
    esac
    CONF=/var/run/vsftpd/vsftpd-dev.conf
    cp /etc/vsftpd/vsftpd.conf "$CONF"
    printf 'pasv_address=%s\n' "$FTP_PASV_ADDRESS" >>"$CONF"
fi

exec vsftpd "$CONF"
