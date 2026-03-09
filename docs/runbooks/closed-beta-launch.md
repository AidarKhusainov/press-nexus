# Closed Beta Launch Runbook

## Goal

Launch and monitor the first closed beta wave for 20 Telegram users with reproducible operator steps.

## Preconditions

- App is running and healthy on `http://localhost:8080`.
- Telegram delivery is enabled and `press.delivery.telegram.bot-token` is configured.
- PostgreSQL is available and migrations are applied.
- Prometheus/Grafana are optional but recommended for daily follow-up.

## 1. Verify app readiness

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS "http://localhost:8080/api/brief/daily?hours=24&limit=3&lang=ru"
```

If the brief is empty, investigate fetch/summarization before inviting beta users.

## 2. Invite beta users

1. Share the Telegram bot link with the initial 20 users.
2. Ask them to complete `/start`, choose topics, and keep delivery enabled.
3. Confirm onboarding signals in logs/metrics:
   - `press.onboarding.completed{channel="telegram"}`
   - `press.onboarding.completion.seconds{channel="telegram"}`

## 3. Trigger first delivery and collect preview/report

Use the helper script:

```bash
./scripts/closed-beta-check.sh \
  --api-base-url http://localhost:8080 \
  --report-date YYYY-MM-DD \
  --trigger-send-now \
  --preview-out /tmp/press-beta-preview.txt \
  --report-json-out /tmp/press-beta-report.json
```

This does three things:

- fetches `/api/brief/daily/text` preview for operator review;
- optionally triggers `POST /api/brief/daily/send`;
- fetches `/api/analytics/product-report/daily` for the chosen date.

## 4. Review quality after first deliveries

Check:

- preview text in `/tmp/press-beta-preview.txt`;
- `deliveryUsers`, `feedbackUsers`, `usefulRatePct`, `noiseRatePct`, `d1RetentionPct`, `d7RetentionPct` in `/tmp/press-beta-report.json`;
- Grafana dashboard `Press Nexus Product Analytics` if monitoring is enabled.

## 5. Update MVP progress once traffic is non-zero

When the report starts showing real traffic:

```bash
./scripts/update-mvp-progress-go-no-go.sh --date YYYY-MM-DD
```

Or from Prometheus snapshot:

```bash
./scripts/update-mvp-progress-go-no-go.sh \
  --date YYYY-MM-DD \
  --prometheus-base-url http://localhost:9090
```

## Rollback / mitigation

- Stop scheduled delivery by setting `press.delivery.telegram.enabled=false`.
- Use `/api/brief/daily/send` only after preview looks acceptable.
- If quality degrades, keep beta audience fixed and adjust ranking/noise heuristics before expanding beyond 20 users.
