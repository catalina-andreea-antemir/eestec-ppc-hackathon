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

# Doar pentru dezvoltare: FTP_PASV_ADDRESS (setat in docker-compose.dev.yml) face vsftpd sa anunte aceasta adresa in
# raspunsul PASV, ca serverul sa poata fi folosit de pe host prin porturile publicate pe 127.0.0.1. Fara variabila
# (configuratia implicita) nu se schimba nimic: se foloseste direct vsftpd.conf din imagine.
CONF=/etc/vsftpd/vsftpd.conf
if [ -n "${FTP_PASV_ADDRESS:-}" ]; then
    case "$FTP_PASV_ADDRESS" in
        *[!0-9.]* | "" | .* | *.)
            echo "FTP_PASV_ADDRESS trebuie sa fie o adresa IPv4." >&2
            exit 1
            ;;
    esac
    CONF=/var/run/vsftpd/vsftpd-dev.conf
    cp /etc/vsftpd/vsftpd.conf "$CONF"
    printf 'pasv_address=%s\n' "$FTP_PASV_ADDRESS" >>"$CONF"
fi

exec vsftpd "$CONF"
