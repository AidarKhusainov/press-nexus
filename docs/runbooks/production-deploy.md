# Production Deploy Runbook

## Goal

Deploy Press Nexus on a real server with protected ingress, externalized config, and reproducible smoke checks.

## Required Environment

```bash
export SPRING_PROFILES_ACTIVE=prod
export PRESS_DB_PASSWORD='<set-db-password>'
export PRESS_API_KEY='<set-internal-api-key>'
export TELEGRAM_WEBHOOK_SECRET_TOKEN='<set-webhook-secret>'
export TELEGRAM_BOT_TOKEN='<set-telegram-bot-token>'
export GEMINI_API_KEY='<set-gemini-api-key>'
export OLLAMA_BASE_URL='http://ollama:11434/'
```

Optional:

```bash
export PRESS_R2DBC_URL='r2dbc:postgresql://postgres:5432/pressnexus'
export PRESS_JDBC_URL='jdbc:postgresql://postgres:5432/pressnexus'
export PRESS_DB_USER='pressnexus'
export PRESS_DELIVERY_TELEGRAM_ENABLED='false'
export PRESS_OTLP_TRACING_ENABLED='false'
export OTLP_TRACING_ENDPOINT='http://otel-collector:4318/v1/traces'
```

## Build

```bash
./scripts/use-jdk21.sh ./mvnw -B -ntp clean verify
docker build -t press-nexus:prod .
```

GitHub Actions CD is available via [docs/runbooks/github-actions-production-cd.md](/home/aidar/work/Pets/press-nexus/docs/runbooks/github-actions-production-cd.md). Keep production host and SSH host key in GitHub secrets or environment secrets, not in the repository.

## Deploy Topology

1. Run PostgreSQL with persistent storage and pgvector enabled.
2. Run Ollama or provide a compatible embedding backend reachable via `OLLAMA_BASE_URL`.
3. Run the app image with the `prod` Spring profile.
4. Put the app behind HTTPS reverse proxy.
5. Expose only:
   - public brief endpoints if needed
   - Telegram webhook path
   - health probes
6. Keep internal/reporting endpoints behind reverse proxy restrictions and `X-PressNexus-Api-Key`.

For a single-server rollout, the CD workflow uploads `deploy/docker-compose.prod.yml` to `/opt/press-nexus` and runs the stack there. If you already have reverse proxy on the server, keep `APP_BIND_ADDRESS=127.0.0.1`.

## Telegram Webhook

After the app is reachable via HTTPS, register Telegram webhook with the same secret token used by the app.

Expected app-side header:

- `X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET_TOKEN`

## Smoke Checks

Health:

```bash
curl -sS https://<host>/actuator/health
```

Public brief preview:

```bash
curl -sS "https://<host>/api/brief/daily/text?hours=24&limit=3&lang=ru"
```

Protected report endpoint:

```bash
curl -sS \
  -H "X-PressNexus-Api-Key: $PRESS_API_KEY" \
  "https://<host>/api/analytics/product-report/daily?date=YYYY-MM-DD"
```

Protected send-now endpoint:

```bash
curl -sS \
  -X POST \
  -H "X-PressNexus-Api-Key: $PRESS_API_KEY" \
  "https://<host>/api/brief/daily/send"
```

## Rollback

1. Disable delivery with `PRESS_DELIVERY_TELEGRAM_ENABLED=false`.
2. Revert app image/version.
3. Rotate `PRESS_API_KEY` and `TELEGRAM_WEBHOOK_SECRET_TOKEN` if ingress exposure is suspected.
4. Keep DB schema changes backward-compatible before rollback.
