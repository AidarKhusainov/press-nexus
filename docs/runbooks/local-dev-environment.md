# Local Development Environment Runbook

## Goal

Initialize reproducible local environment for humans and AI-agents.

## One-command setup

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

