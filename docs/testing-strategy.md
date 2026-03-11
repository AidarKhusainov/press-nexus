# Testing Strategy

## Goals

- Early regression detection.
- Stable behavior of ingest-enrich-delivery pipeline.
- Contract safety for all public interfaces.

## Test Pyramid

- Unit tests:
  - domain logic, utilities, pure formatting logic
  - fast and deterministic
- Contract tests:
  - HTTP contracts for controllers (`*ContractTest`)
  - contract artifact checks (`docs/contracts/openapi.yaml`, event schemas)
- Architecture tests:
  - ArchUnit rules for package boundaries and dependency direction
- Integration tests:
  - DB/repository boundaries
  - multi-component orchestration
- Live tests:
  - real external sources
  - manual only, must not break mandatory CI

## Rules

- Every bug fix must include regression test.
- Contract changes require OpenAPI/event-schema + contract tests update.
- Flaky tests are not allowed in mandatory CI pipeline.
- Mandatory CI covers analyzers plus deterministic tests only.
- Commands and mandatory gates are defined in `docs/quality-gates.md`.
