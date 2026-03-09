# Change Management Runbook

## Before Merge

- Verify DoD checklist.
- Verify impact on contracts and migrations.
- Ensure rollback plan exists.

## Before Deploy

- Verify dependency availability (DB/Ollama/queues).
- Verify config and env compatibility.
- Verify `PRESS_API_KEY` and `TELEGRAM_WEBHOOK_SECRET_TOKEN` are set for the target environment.
- Verify alerts and dashboards.

## After Deploy

- Monitor error rate/latency/queue lag for at least 30 minutes.
- If deviations occur, execute rollback/feature disable.
