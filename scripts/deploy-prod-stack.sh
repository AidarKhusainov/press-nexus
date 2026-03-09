#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/press-nexus}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
APP_PORT="${APP_PORT:-}"

log() {
	printf '[deploy-prod] %s\n' "$*"
}

read_env() {
	local key="$1"
	local value
	value="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d '=' -f 2- || true)"
	printf '%s' "$value"
}

if [[ ! -d "$APP_DIR" ]]; then
	echo "APP_DIR does not exist: $APP_DIR" >&2
	exit 1
fi

cd "$APP_DIR"

if [[ ! -f "$COMPOSE_FILE" ]]; then
	echo "Compose file not found: $APP_DIR/$COMPOSE_FILE" >&2
	exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
	echo "Environment file not found: $APP_DIR/$ENV_FILE" >&2
	exit 1
fi

if docker compose version >/dev/null 2>&1; then
	DOCKER_COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
	DOCKER_COMPOSE=(docker-compose)
else
	echo "Docker Compose is required on the target server." >&2
	exit 1
fi

GHCR_USERNAME="${GHCR_USERNAME:-$(read_env GHCR_USERNAME)}"
GHCR_TOKEN="${GHCR_TOKEN:-$(read_env GHCR_TOKEN)}"
PRESS_API_KEY="$(read_env PRESS_API_KEY)"
APP_PORT="${APP_PORT:-$(read_env APP_PORT)}"
APP_PORT="${APP_PORT:-8080}"

if [[ -z "$GHCR_USERNAME" || -z "$GHCR_TOKEN" ]]; then
	echo "GHCR_USERNAME and GHCR_TOKEN must be present in $ENV_FILE" >&2
	exit 1
fi

if [[ -z "$PRESS_API_KEY" ]]; then
	echo "PRESS_API_KEY must be present in $ENV_FILE" >&2
	exit 1
fi

log "Logging into ghcr.io"
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin

log "Starting Postgres and Ollama"
"${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d postgres ollama

log "Waiting for Ollama control plane"
for _ in $(seq 1 30); do
	if "${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T ollama ollama list >/dev/null 2>&1; then
		break
	fi
	sleep 2
done

log "Ensuring embedding model nomic-embed-text is present"
"${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T ollama ollama pull nomic-embed-text

log "Pulling and starting application"
"${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull app
"${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d app

log "Waiting for application health"
for _ in $(seq 1 40); do
	if curl --fail --silent --show-error "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
		break
	fi
	sleep 3
done

log "Running smoke checks"
curl --fail --silent --show-error "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null
curl --fail --silent --show-error \
	-H "X-PressNexus-Api-Key: ${PRESS_API_KEY}" \
	"http://127.0.0.1:${APP_PORT}/api/analytics/product-report/daily?date=$(date -u -d 'yesterday' +%F)" >/dev/null

log "Current compose status"
"${DOCKER_COMPOSE[@]}" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps

log "Pruning dangling images"
docker image prune -f >/dev/null 2>&1 || true
