# Local Development Environment Runbook

## Goal

Initialize reproducible local environment for humans and AI-agents.

## One-command setup

Set required DB password in environment:

```bash
export PRESS_DB_PASSWORD='<set-db-password>'
```

If monitoring stack is enabled, set Grafana admin password too:

```bash
export GRAFANA_ADMIN_PASSWORD='<set-grafana-password>'
```

```bash
docker compose -f docker/compose.yml up -d app
```

This starts:

- PostgreSQL (`pressnexus_postgres`)
- local AI backend (`ollama-cpu`)
- one-shot model bootstrap for `nomic-embed-text`
- application (`app`) on `http://localhost:8080`

Published ports are bound to `127.0.0.1` by default. Override `APP_BIND_ADDRESS`, `POSTGRES_BIND_ADDRESS`, `OLLAMA_BIND_ADDRESS`, or the corresponding `*_PORT` variables only when you intentionally need remote access from outside the host.

PostgreSQL extensions are initialized via `docker/postgres/initdb.d`, so no follow-up `docker exec` step is required.

If the local `pgdata` volume already exists, reuse the same `PRESS_DB_PASSWORD` that was used during the first bootstrap. `POSTGRES_PASSWORD` is not re-applied to an existing data directory.
The first startup may also spend extra time downloading the Ollama embedding model before the app is started.

## Manual steps (if needed)

1. Start DB:
```bash
docker compose -f docker/compose.yml up -d postgres
```
2. Start AI backend:
```bash
docker compose -f docker/compose.yml up -d ollama-cpu
```
3. Start application:
```bash
docker compose -f docker/compose.yml up -d app
```

## Verify

```bash
./mvnw -B -ntp -DskipTests validate
```

## Optional monitoring stack

```bash
docker compose -f docker/compose.yml --profile monitoring up -d prometheus grafana loki promtail
```

If you want the GPU-backed Ollama service instead of the default CPU profile, start it explicitly:

```bash
docker compose -f docker/compose.yml --profile gpu up -d ollama-gpu
```

After the app starts, the product-report scheduler is enabled by default. It publishes the previous day's `press.product.report.*` snapshot roughly 45 seconds after startup and then every 24 hours, so `docs/MVP_PROGRESS.md` can be refreshed manually from Prometheus/Grafana when beta traffic appears.
