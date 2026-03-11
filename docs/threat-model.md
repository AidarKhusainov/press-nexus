# Threat Model (Baseline)

## Scope

- HTTP APIs (`/api/**`)
- Telegram webhook ingress
- External source fetching pipeline
- DB persistence layer
- Metrics/logging pipeline

## Assets

- User identifiers (`chatId`)
- Feedback and analytics events
- News content and derived summaries
- Service credentials and API tokens

## Trust Boundaries

- Internet -> API ingress
- Service -> external source APIs
- Service -> database
- Service -> observability backends

## Threats and Controls

| Threat | Vector | Control |
|---|---|---|
| Spoofed webhook/event payload | Public endpoint abuse | Input validation, contract checks, Telegram secret header validation, error-safe handling |
| Unauthorized operational API access | Public access to internal endpoints | Internal API key on operational/reporting endpoints, reverse-proxy restrictions |
| Sensitive data leakage in logs | Logging raw payloads | Structured logging, redact sensitive fields |
| Dependency supply-chain risk | Vulnerable transitive deps | Dependabot + regular dependency review |
| DoS via external latency | Slow/unavailable upstreams | Timeouts, retries, bounded queues, fallback |
| Data corruption from retries | Duplicate processing | Idempotent pipeline and upsert semantics |
| Unauthorized secret exposure | Hardcoded credentials | Env/secret manager policy and review |

## Review Cadence

- Review quarterly.
- Mandatory review on architecture changes.
