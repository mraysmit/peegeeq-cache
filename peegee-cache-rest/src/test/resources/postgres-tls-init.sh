#!/bin/sh
set -eu

cp /docker-entrypoint-initdb.d/server.crt "$PGDATA/server.crt"
cp /docker-entrypoint-initdb.d/server.key "$PGDATA/server.key"
chmod 600 "$PGDATA/server.key"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
ALTER SYSTEM SET ssl = 'on';
ALTER SYSTEM SET ssl_cert_file = 'server.crt';
ALTER SYSTEM SET ssl_key_file = 'server.key';
SQL
