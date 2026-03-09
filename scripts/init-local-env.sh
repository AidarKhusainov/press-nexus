#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

: "${PRESS_DB_PASSWORD:?Set PRESS_DB_PASSWORD before running ./scripts/init-local-env.sh}"

./scripts/init-local-db.sh

echo "Starting local AI backend..."
docker compose up -d ollama-cpu

echo "Optional: monitoring stack"
echo "  docker compose --profile monitoring up -d prometheus grafana loki promtail"

echo "Local environment is initialized."
