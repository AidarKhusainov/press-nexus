# Non-Functional Requirements

## SLA / SLO

- Availability SLO for HTTP API (`/api/**`): >= 99.5% monthly.
- Pipeline freshness SLO: 95% news items processed within 10 minutes from fetch.
- Error budget policy: if monthly error budget is exhausted, feature work pauses until reliability issues are fixed.

## Performance

- API latency target: p95 <= 500ms, p99 <= 1200ms for internal endpoints under normal load.
- External HTTP timeouts:
  - connection timeout <= 60s
  - read timeout <= 300s
- DB backlog target: pending backlog should stay bounded and observable; alerting is based on backlog size/age, not readiness state.
- Embedding throughput should use batched backend requests and bounded stage concurrency so backlog can be reduced without unbounded in-memory fan-out.

## Reliability

- Pipeline stages must be idempotent.
- Retries must be bounded and observable.
- Any external dependency failure should degrade gracefully, not crash the whole app.
- Stage workers must use claim/lease semantics so parallel workers do not process the same item concurrently.
- `FAILED` items must not silently re-enter the hot backlog without an explicit recovery policy.

## Scalability

- Stateless components should support horizontal scaling.
- Pipeline backlog must tolerate horizontal worker scaling via DB claim/lease before any broker is introduced.

## Observability

- Structured logs enabled by default.
- Trace correlation is mandatory (`traceId`/`spanId` in logs when tracing enabled).
- Required metrics:
  - throughput
  - error rate
  - pipeline backlog by stage/state
  - external HTTP latency and error ratio
  - scheduler run/failure counters
  - scheduler skip counters
  - daily product-report snapshot gauges for D1/D7 retention and Useful/Noise rates
- Health endpoints must expose readiness/liveness.

## Security

- Secrets must be passed via env/secret manager only.
- Principle of least privilege for DB/API integrations.
- Dependency updates must stay under regular review; blocking CI is reserved for the mandatory Maven quality gates and tests.

## Limits and Guardrails

- Max `hours` for brief endpoint: 168.
- Max `limit` for brief endpoint: 20.
- Any change of limits must be reflected in OpenAPI and contract tests.
