#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

: "${PRESS_DB_PASSWORD:?Set PRESS_DB_PASSWORD before running ./scripts/init-local-db.sh}"

echo "Starting postgres container..."
docker compose up -d postgres

echo "Waiting for postgres readiness..."
for _ in $(seq 1 60); do
  if docker exec pressnexus_postgres pg_isready -U pressnexus -d pressnexus >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec pressnexus_postgres pg_isready -U pressnexus -d pressnexus >/dev/null

echo "Ensuring extensions and baseline schema..."
docker exec -i pressnexus_postgres psql -U pressnexus -d pressnexus <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL

echo "Database is ready."
