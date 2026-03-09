# Observability Baseline

## Required Signals

- Logs: structured, correlation-friendly.
- Metrics: queue depth, pipeline stage events, external HTTP outcomes, scheduler outcomes.
- Traces: trace context must propagate through request and async processing.
- Health: actuator health/readiness endpoints.
- Alerts: Prometheus alert rules under `monitoring/prometheus/alerts.yml`.

## Dashboards

- `Press Nexus Overview`
- `Press Nexus Logs`

## Operational Rule

Any new critical flow must include at least one metric and one actionable log event.

