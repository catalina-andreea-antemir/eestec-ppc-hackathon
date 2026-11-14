#!/bin/sh
# Creeaza (sau actualizeaza) utilizatorul FTP din variabilele de mediu si porneste vsftpd in prim-plan.
set -eu

: "${FTP_USER:?Lipseste FTP_USER}"
: "${FTP_PASSWORD:?Lipseste FTP_PASSWORD}"

case "$FTP_USER" in
    *[!a-z0-9_-]* | "" | -*)
        echo "FTP_USER poate contine doar litere mici, cifre, '_' si '-'." >&2
        exit 1
        ;;
esac

if ! id "$FTP_USER" >/dev/null 2>&1; then
    adduser -D -H -h /srv/ftp -s /sbin/nologin -u 1000 "$FTP_USER"
fi
# chpasswd citeste din stdin, deci parola nu apare in lista de procese.
printf '%s:%s\n' "$FTP_USER" "$FTP_PASSWORD" | chpasswd >/dev/null 2>&1
printf '%s\n' "$FTP_USER" >/etc/vsftpd/user_list

# vsftpd cere ca shell-ul utilizatorului sa fie in /etc/shells.
grep -qx /sbin/nologin /etc/shells || echo /sbin/nologin >>/etc/shells

chown "$FTP_USER:$FTP_USER" /srv/ftp
chmod 755 /srv/ftp

exec vsftpd /etc/vsftpd/vsftpd.conf
