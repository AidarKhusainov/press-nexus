# Definition of Done

## Scope

This document is mandatory for all feature/fix/refactor tasks.

## DoD Checklist

- Functionality is implemented and covered by tests at the right level.
- Passed: `./mvnw clean verify`.
- CI in PR is green.
- Risks are documented in PR (including backward compatibility).
- Observability is updated: metrics/logs/alerts when needed.
- Documentation updated: README/CONTRIBUTING/architecture/runbook.
- ADR created/updated for architecture changes.
- For API/event contract changes, `docs/contracts/*` and `*ContractTest` are updated.
- Rollback/mitigation plan prepared for migrations/operations.

## Test Levels

- Unit: deterministic and fast.
- Integration: module/repository boundaries.
- Contract/Behavior: public API and event format validation.

## What Is Not Done

- “Works on my machine” without CI.
- Critical-path code without observability.
- Contract change without compatibility strategy.
