#!/usr/bin/env bash
# Runs only when this owned local volume is initialized for the first time.
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv migration_password SLOTKEEPER_MIGRATION_PASSWORD
\getenv app_password SLOTKEEPER_APP_PASSWORD
CREATE ROLE slotkeeper_migrator LOGIN PASSWORD :'migration_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE slotkeeper_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT, CREATE ON DATABASE slotkeeper TO slotkeeper_migrator;
GRANT CONNECT ON DATABASE slotkeeper TO slotkeeper_app;
SQL
