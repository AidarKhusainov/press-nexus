# Production Deploy Runbook

## Goal

Deploy Press Nexus on a real server with Docker Compose and protected ingress.

## Preferred Path

Use GitHub Actions CD for normal production rollouts.

## GitHub Actions CD

- CI publishes `ghcr.io/<repo>:<full-commit-sha>` on successful pushes to `master`
- CI also publishes `ghcr.io/<repo>:prod` on `master`
- CD runs automatically after successful `CI` on `master`
- CD can also be started manually with `workflow_dispatch`
- CD uploads `docker/compose.prod.yml` and `monitoring/**` to `/opt/press-nexus`
- CD logs into GHCR on the server and runs `docker compose pull/up`
- CD starts Prometheus, Grafana, Loki, and Promtail together with the app stack

### Required GitHub Secrets

- `PROD_SSH_USER`
- `PROD_HOST`
- `PROD_SSH_KEY`
- `PROD_GHCR_USERNAME`
- `PROD_GHCR_TOKEN`
- `PRESS_DB_PASSWORD`
- `PRESS_API_KEY`
- `GEMINI_API_KEY`
- `GRAFANA_ADMIN_PASSWORD`
- `TELEGRAM_WEBHOOK_SECRET_TOKEN` - optional if webhook is enabled
- `TELEGRAM_BOT_TOKEN` - optional if Telegram delivery is enabled

### Optional GitHub Variables

- `PRESS_DB_USER` - defaults to `pressnexus`
- `PRESS_DELIVERY_TELEGRAM_ENABLED` - defaults to `false`
- `PRESS_OTLP_TRACING_ENABLED` - defaults to `false`
- `APP_BIND_ADDRESS` - defaults to `127.0.0.1`
- `APP_PORT` - defaults to `8080`
- `APP_MEMORY` - defaults to `3g`
- `OLLAMA_CPUS` - defaults to `6.0`
- `OLLAMA_MEMORY` - defaults to `8g`
- `OLLAMA_THREADS` - defaults to `6`
- `OLLAMA_NUM_PARALLEL` - defaults to `4`
- `PRESS_NEWS_PIPELINE_EMBEDDING_CONCURRENCY` - defaults to `4`
- `JAVA_TOOL_OPTIONS` - defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`
- `PRESS_R2DBC_URL`
- `PRESS_JDBC_URL`
- `OLLAMA_BASE_URL`
- `OTLP_TRACING_ENDPOINT`
- `POSTGRES_IMAGE`
- `OLLAMA_IMAGE`
- `NODE_EXPORTER_IMAGE`
- `PROMETHEUS_BIND_ADDRESS` - defaults to `127.0.0.1`
- `PROMETHEUS_PORT` - defaults to `9090`
- `GRAFANA_BIND_ADDRESS` - defaults to `127.0.0.1`
- `GRAFANA_PORT` - defaults to `3000`
- `GRAFANA_ADMIN_USER` - defaults to `admin`
- `LOKI_BIND_ADDRESS` - defaults to `127.0.0.1`
- `LOKI_PORT` - defaults to `3100`

## Server Prerequisites

- Docker Engine
- Docker Compose plugin or `docker-compose`
- reverse proxy or other HTTPS ingress in front of the app
- persistent storage for PostgreSQL data and Ollama models
- outbound internet access from containers for external integrations and the initial Ollama model pull

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
7. Keep published ports bound to loopback unless a reverse proxy or ingress explicitly needs wider exposure.

For a single-server rollout, keep `APP_BIND_ADDRESS=127.0.0.1` when a reverse proxy is installed.
Keep PostgreSQL on the internal backend network only. The app, Ollama, and `ollama-model-init` must also join an egress-capable network; otherwise `ollama-model-init` cannot fetch `nomic-embed-text`, and the `app` service will stay in `Created` because it depends on that init job succeeding.
Monitoring ports should also stay on loopback and be accessed through SSH tunnel.
The production app container now defaults to `3g` and the JVM exits on `OutOfMemoryError`, so Docker can restart the service instead of leaving it running but unhealthy.
The production Ollama container now defaults to `6` vCPU / `8g` RAM with `OLLAMA_NUM_PARALLEL=4`, and the app drives embedding with batched requests plus `PRESS_NEWS_PIPELINE_EMBEDDING_CONCURRENCY=4`.

## Manual Fallback

If GitHub Actions is unavailable, export the required runtime variables in the shell and run:

```bash
docker compose -f docker/compose.prod.yml pull app
docker compose -f docker/compose.prod.yml up -d app prometheus node-exporter grafana loki promtail
```

## Rollback

### Preferred Rollback

1. Trigger `workflow_dispatch`.
2. Provide the previous image tag in `image_tag`.
3. Run the deploy again.

### Manual Rollback

1. Disable delivery with `PRESS_DELIVERY_TELEGRAM_ENABLED=false`.
2. Revert app image/version.
3. Rotate `PRESS_API_KEY` and `TELEGRAM_WEBHOOK_SECRET_TOKEN` if ingress exposure is suspected.
4. Keep DB schema changes backward-compatible before rollback.

## Telegram Webhook

After the app is reachable via HTTPS, register Telegram webhook with the same secret token used by the app.

Expected app-side header:

- `X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET_TOKEN`

## Smoke Checks

```bash
curl -sS https://<host>/actuator/health
```

```bash
curl -sS "https://<host>/api/brief/daily/text?hours=24&limit=3&lang=ru"
```

```bash
curl -sS \
  -H "X-PressNexus-Api-Key: $PRESS_API_KEY" \
  "https://<host>/api/analytics/product-report/daily?date=YYYY-MM-DD"
```

```bash
curl -sS \
  -X POST \
  -H "X-PressNexus-Api-Key: $PRESS_API_KEY" \
  "https://<host>/api/brief/daily/send"
```

## Monitoring Access

After deploy, tunnel monitoring ports from your machine:

```bash
ssh -Nf \
  -L 3000:127.0.0.1:3000 \
  -L 9090:127.0.0.1:9090 \
  -L 3100:127.0.0.1:3100 \
  <user>@<host> -p <port>
```

Then open:

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- Loki API: `http://127.0.0.1:3100`

Grafana dashboards are provisioned automatically:

- `Press Nexus Overview`
- `Press Nexus Logs`
- `Press Nexus Product Analytics`
- `Press Nexus Server Overview`

## Notes

- Secrets live in GitHub Actions and are passed only for the deploy command.
- The workflow deploys the app stack itself; ingress and Telegram webhook management stay outside this workflow.
- The current CD flow intentionally skips SSH host verification and post-deploy smoke checks.
