# Change Management Runbook

## Before Merge

- Verify DoD checklist.
- Verify impact on contracts and migrations.
- Ensure rollback plan exists.

## Before Deploy

- Verify dependency availability (DB/Ollama/queues).
- Verify config and env compatibility.
- Verify alerts and dashboards.

## After Deploy

- Monitor error rate/latency/queue lag for at least 30 minutes.
- If deviations occur, execute rollback/feature disable.
