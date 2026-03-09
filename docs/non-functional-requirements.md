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
- Queue consumer lag target: <= 1000 items for 95% of runtime.

## Reliability

- Pipeline stages must be idempotent.
- Retries must be bounded and observable.
- Any external dependency failure should degrade gracefully, not crash the whole app.

## Scalability

- Stateless components should support horizontal scaling.
- Queue architecture must allow migration from in-memory queue to external broker without contract break.

## Observability

- Structured logs enabled by default.
- Trace correlation is mandatory (`traceId`/`spanId` in logs when tracing enabled).
- Required metrics:
  - throughput
  - error rate
  - queue depth
  - external HTTP latency and error ratio
  - scheduler run/failure counters
  - daily product-report snapshot gauges for D1/D7 retention and Useful/Noise rates
- Health endpoints must expose readiness/liveness.

## Security

- Secrets must be passed via env/secret manager only.
- Principle of least privilege for DB/API integrations.
- Dependency updates and security scanning are mandatory in CI.

## Limits and Guardrails

- Max `hours` for brief endpoint: 168.
- Max `limit` for brief endpoint: 20.
- Any change of limits must be reflected in OpenAPI and contract tests.
