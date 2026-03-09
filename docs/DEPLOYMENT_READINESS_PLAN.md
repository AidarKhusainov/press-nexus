# Deployment Readiness Plan

Last updated: 2026-03-09

## Goal

Prepare Press Nexus for deployment to a real server without changing MVP product scope.

## Remaining MVP Tasks

1. Launch the first closed beta wave for 20 Telegram users.
2. Collect non-zero delivery and feedback traffic in `/api/analytics/product-report/daily`.
3. Validate the pending go/no-go criteria from `docs/MVP_PROGRESS.md`:
   - duplicate reduction target
   - onboarding under 1 minute
   - Useful >= 70%
   - Noise <= 20%
   - D7 retention >= 35%
4. Tune digest quality from real beta feedback.
5. Expand beta to 50-100 users only after the first fixes land.

## Production Blockers

### 1. Ingress Security

- Protect internal HTTP endpoints with an API key.
- Validate Telegram webhook requests with `X-Telegram-Bot-Api-Secret-Token`.
- Keep only health probes public by default.

### 2. Production Runtime

- Add a production Spring profile with env-based configuration.
- Document required env vars and bootstrap order.
- Provide a production packaging path for the app image.

### 3. Observability And Operations

- Confirm Prometheus/Grafana/Loki wiring on the target server.
- Protect non-health actuator endpoints from public access.
- Keep helper scripts compatible with internal endpoint auth.

### 4. Deployment Workflow

- Build a production image.
- Run behind HTTPS reverse proxy.
- Verify DB migrations, AI backends, Telegram webhook, and smoke checks before enabling scheduled delivery.

## Immediate Work Order

1. Add HTTP auth hardening in the application.
2. Add production config and deployment runbook.
3. Update contracts/docs/scripts/tests for the hardened access model.
4. Run full local verify.
5. After deploy, use `docs/runbooks/closed-beta-launch.md` to start beta traffic collection.
