#!/usr/bin/env bash
set +x
set -euo pipefail

: "${MSSQL_SA_PASSWORD:?Lipsește MSSQL_SA_PASSWORD}"
: "${META_DB_PASSWORD:?Lipsește META_DB_PASSWORD}"
: "${STORAGE_DB_PASSWORD:?Lipsește STORAGE_DB_PASSWORD}"

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
storage_host=${STORAGE_DB_HOST:-mssql}
storage_admin_password=${STORAGE_DB_SA_PASSWORD:-$MSSQL_SA_PASSWORD}
sqlcmd=/opt/mssql-tools18/bin/sqlcmd

if [[ ! -x "$sqlcmd" ]]; then
    printf 'sqlcmd from mssql-tools18 is missing.\n' >&2
    exit 1
fi

run_sql() {
    local host=$1 login=$2 password=$3 database=$4
    shift 4
    # -I = QUOTED_IDENTIFIER ON; without it, CREATE TABLE with a persisted computed column (blob_chunks) fails.
    SQLCMDPASSWORD="$password" "$sqlcmd" -S "$host" -U "$login" -d "$database" \
        -C -I -b -x -l 3 "$@"
}

wait_for_sql() {
    local host=$1 password=$2 attempt
    for ((attempt = 1; attempt <= 15; attempt++)); do
        if run_sql "$host" sa "$password" master -t 3 -Q 'SET NOCOUNT ON; SELECT 1;' \
            </dev/null >/dev/null 2>&1; then
            return 0
        fi
        if ((attempt < 15)); then
            sleep 2
        fi
    done
    printf 'SQL Server did not become reachable at %s. Check the server and the initialization credentials.\n' "$host" >&2
    return 1
}

# Hex-encoded UTF-16LE keeps every password character intact, with no sqlcmd variable substitution.
password_hex() {
    printf '%s' "$1" | iconv -f UTF-8 -t UTF-16LE | od -An -v -tx1 | tr -d '[:space:]'
}

initialize_database() {
    local host=$1 admin_password=$2 database=$3 login=$4 password=$5 hex=$6 script=$7

    # sqlcmd output is filtered so not even SQL errors can print the password.
    if ! {
        cat <<SQL
IF SUSER_ID(N'$login') IS NULL
BEGIN
    DECLARE @password nvarchar(128) = CONVERT(nvarchar(128), 0x$hex);
    DECLARE @ddl nvarchar(max) = N'CREATE LOGIN [$login] WITH PASSWORD = N'''
        + REPLACE(@password, N'''', N'''''') + N''', CHECK_POLICY = ON;';
    EXEC sys.sp_executesql @ddl;
END;
GO
SQL
        cat -- "$script"
    } | run_sql "$host" sa "$admin_password" master -t 60 >/dev/null 2>&1; then
        printf 'SQL initialization failed for %s. Check the password policy, the database and the existing accounts.\n' "$database" >&2
        return 1
    fi

    if ! run_sql "$host" "$login" "$password" "$database" -t 3 -Q 'SET NOCOUNT ON; SELECT 1;' \
        </dev/null >/dev/null 2>&1; then
        printf 'App account login failed for %s. Check the password and the existing login.\n' "$database" >&2
        return 1
    fi
    printf '%s is ready.\n' "$database"
}

if ! meta_hex=$(password_hex "$META_DB_PASSWORD") ||
   ! storage_hex=$(password_hex "$STORAGE_DB_PASSWORD") ||
   [[ ! "$meta_hex" =~ ^[0-9a-f]{4,512}$ || ! "$storage_hex" =~ ^[0-9a-f]{4,512}$ ]]; then
    printf 'App passwords must be UTF-8 and fit in 128 UTF-16 code units.\n' >&2
    exit 1
fi

wait_for_sql mssql "$MSSQL_SA_PASSWORD"
if [[ "$storage_host" != mssql || "$storage_admin_password" != "$MSSQL_SA_PASSWORD" ]]; then
    wait_for_sql "$storage_host" "$storage_admin_password"
fi

initialize_database mssql "$MSSQL_SA_PASSWORD" FileGridMeta filegrid_meta_app \
    "$META_DB_PASSWORD" "$meta_hex" init-meta.sql
initialize_database "$storage_host" "$storage_admin_password" FileGridStorage filegrid_storage_app \
    "$STORAGE_DB_PASSWORD" "$storage_hex" init-storage.sql
