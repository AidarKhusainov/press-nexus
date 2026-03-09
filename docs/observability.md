# Observability Baseline

## Required Signals

- Logs: structured, correlation-friendly.
- Metrics: queue depth, pipeline stage events, external HTTP outcomes, scheduler outcomes.
- Onboarding: completion counter `press.onboarding.completed{channel="telegram"}` and completion duration `press.onboarding.completion.seconds{channel="telegram"}`.
- Daily brief tone moderation: counter `press.brief.tone.moderation{outcome,importance}` for accepted/rejected cards by importance tier.
- Traces: trace context must propagate through request and async processing.
- Health: actuator health/readiness endpoints.
- Alerts: Prometheus alert rules under `monitoring/prometheus/alerts.yml`.

## Dashboards

- `Press Nexus Overview`
- `Press Nexus Logs`
- `Press Nexus Product Analytics`

## Operational Rule

Any new critical flow must include at least one metric and one actionable log event.
