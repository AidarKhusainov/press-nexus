# Observability Baseline

## Required Signals

- Logs: structured, correlation-friendly.
- Metrics: DB backlog by stage/state, pipeline stage events, external HTTP outcomes, scheduler outcomes.
- Onboarding: completion counter `press.onboarding.completed{channel="telegram"}` and completion duration `press.onboarding.completion.seconds{channel="telegram"}`.
- Daily brief tone moderation: counter `press.brief.tone.moderation{outcome,importance}` for accepted/rejected cards by importance tier.
- Product analytics: daily snapshot gauges `press.product.report.*` must be published automatically on startup and then every 24h for the previous day, so Go/No-Go metrics remain available in Prometheus/Grafana and in `docs/MVP_PROGRESS.md`.
- Traces: trace context must propagate through request and async processing.
- Health: actuator health/readiness endpoints.
- Alerts: Prometheus alert rules under `monitoring/prometheus/alerts.yml`.

## Dashboards

- `Press Nexus Overview`
- `Press Nexus Logs`
- `Press Nexus Product Analytics`
- `Press Nexus Server Overview`

## Infrastructure Metrics

- Host metrics are scraped from `node_exporter` under Prometheus job `press-nexus-node`.
- Required host-level signals: CPU usage, load average, memory usage, filesystem utilization, disk IO, and network throughput.

## Operational Rule

Any new critical flow must include at least one metric and one actionable log event.

## Pipeline Baseline

- Backpressure is enforced via bounded stage concurrency and PostgreSQL backlog, not via in-memory `Sinks.Many` buffers.
- Required pipeline metrics:
  - `press.pipeline.backlog{stage,state}`
  - `press.pipeline.stage.events{stage,outcome}`
  - `press.pipeline.stage.duration{stage,outcome}`
  - `press.jobs.runs{job,outcome}`
  - `press.jobs.skipped{job,reason}` for discovery throttling on high backlog
- Pipeline backlog should be inspected from metrics/dashboard first; if ad-hoc SQL is needed, the `news` table uses `status_content`, `status_embedding`, and `status_summary`.
- Embedding throughput tuning must be validated against `press.pipeline.backlog{stage="embedding",state=~"pending|in_progress"}`, `press.pipeline.stage.duration{stage="embedding"}`, and `press_http_client_duration_seconds{client="OLLAMA"}`.
- Summarization provider rollouts must be validated against `press_http_client_duration_seconds{client=~"GEMINI|GROQ|CLOUDFLARE_WORKERS_AI|MISTRAL"}` and matching external error metrics before switching `press.ai.summarization.provider` in production.
- Gemini investigations must look at both provider-level quota and app-side guardrails:
  - `429` should correlate with `press_http_client_requests{client="GEMINI",outcome="client_error",status="429"}` and summary backlog staying leased instead of flipping to `FAILED`
  - `429` must not generate transport-layer hot-loop retries for Gemini; if retries appear, inspect `platform.gemini.http.retry.*` configuration first
  - `400` logs must include the provider response body and input size so malformed/oversized prompts can be diagnosed without reproducing on prod
  - repeated `429`/transport failures should eventually switch the local `resilience4j` circuit breaker to open state, so the app stops sending hot-loop retries until the cool-down expires
- Summary-stage triage should be inspected with both logs and backlog metrics:
  - representative clusters outside the daily top-N or outside the remaining daily bucket should log a fallback/defer reason instead of silently disappearing
  - duplicate news items should normally move through cache inheritance rather than external-provider latency
  - `press.pipeline.backlog{stage="summary",state="in_progress"}` can legitimately grow during the maturity window; alerting should focus on sustained age, not a brief delay after ingest
- Provider fallback investigations should correlate:
  - the first provider failure log entry
  - the fallback-provider success/failure log entry
  - external HTTP metrics by provider, to confirm whether traffic shifted away from a throttled or degraded vendor
- Backlog dashboards and alerts should distinguish `pending`/`in_progress` from `failed`; only active backlog should drive discovery throttling.
- Readiness must reflect app/runtime dependencies; backlog is an alert/SLO signal, not a readiness gate.
- Similarity threshold changes must be validated with cluster-size distribution checks so representative-news selection does not collapse into giant connected components.
