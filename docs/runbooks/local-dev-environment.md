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
./scripts/init-local-env.sh
```

This starts:

- PostgreSQL (`pressnexus_postgres`)
- local AI backend (`ollama-cpu`)

## Manual steps (if needed)

1. Start DB:
```bash
docker compose up -d postgres
```
2. Ensure DB extensions:
```bash
./scripts/init-local-db.sh
```
3. Start AI backend:
```bash
docker compose up -d ollama-cpu
```

## Verify

```bash
./scripts/use-jdk21.sh ./mvnw -B -ntp -DskipTests validate
```

## Optional monitoring stack

```bash
docker compose --profile monitoring up -d prometheus grafana loki promtail
```

After the app starts, the product-report scheduler is enabled by default. It publishes the previous day's `press.product.report.*` snapshot roughly 45 seconds after startup and then every 24 hours, so `docs/MVP_PROGRESS.md` can be refreshed from Prometheus with:

```bash
./scripts/update-mvp-progress-go-no-go.sh --date YYYY-MM-DD --prometheus-base-url http://localhost:9090
```
