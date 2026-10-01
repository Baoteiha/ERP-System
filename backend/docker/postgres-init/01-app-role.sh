#!/bin/sh
# Creates the app role row-level security binds (V22 would otherwise create it on first migrate).
# The postgres image runs this once, when it initialises an empty data volume.
set -e
psql -v ON_ERROR_STOP=1 -v app_password="$ERP_APP_PASSWORD" \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE erp_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
SQL
