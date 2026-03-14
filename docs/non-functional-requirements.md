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
- Summarization provider selection must stay configuration-driven; every provider uses the shared HTTP client policy for bounded retries, timeouts, and metrics.
- Gemini summarization must respect provider guardrails before the HTTP call:
  - conservative request pacing at or below the configured Gemini RPM guardrail (`2 RPM` by default)
  - bounded daily request budget aligned with the configured Gemini RPD guardrail (`20 RPD` by default)
  - bounded input size so oversized articles do not produce opaque `400 BAD_REQUEST`
  - library-based resilience via `resilience4j` `RateLimiter` and `CircuitBreaker`, so repeated `429`/transient transport failures stop hot-loop retries
  - shared HTTP retry policy must remain provider-agnostic; provider-specific `429` handling must come from provider-owned logic or per-client configuration, not name-based branching in shared transport code
- Scarce-provider usage must be optimized for user value:
  - automatic cluster summarization is limited to daily top-N representatives ranked by cluster size, freshness, and source quality
  - non-representative duplicates must inherit a cached representative summary instead of calling an external model again
  - article-level summary cache must be reused by `news_id` and `content_hash` before any provider call
  - automatic cluster summarization must wait for a short maturity window before spending a provider request on a developing story
- Summarization routing must support provider failover:
  - primary provider remains configuration-driven
  - throttling and transient provider failures must fall through to configured secondary providers before a cheap local fallback is used
  - budget buckets must keep capacity for automatic clusters, explicit user-facing requests, and reserve/emergency traffic separately
- Cheap fallback summaries must remain available when all external providers are unavailable; degraded output is acceptable, empty output is not.
- DB backlog target: pending backlog should stay bounded and observable; alerting is based on backlog size/age, not readiness state.
- Embedding throughput should use batched backend requests and bounded stage concurrency so backlog can be reduced without unbounded in-memory fan-out.
- Similarity/clustering must use true cosine semantics for normalized embeddings; any threshold rollout must be validated on real pairwise score distribution before production enablement.

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
