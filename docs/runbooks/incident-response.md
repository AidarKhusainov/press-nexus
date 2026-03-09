# Incident Response Runbook

## When To Use

- Sudden spike in pipeline errors.
- Digest delivery degradation.
- External source/AI backend/DB issues.

## Triage Steps

1. Record start time and impact.
2. Check health endpoints and key metrics.
3. Check logs by correlation/source id.
4. Localize affected component (fetch/populate/embed/summarize/delivery).
5. Apply mitigation (disable source, reduce rate, switch fallback).

## Communication

- Post status updates every 30 minutes until stabilized.
- After recovery, run a postmortem with action items.

## Minimum Postmortem

- Root cause.
- Why early signals did not catch it.
- What will be changed in code/monitoring/process.
- Deadlines and owner for action items.
