# Contributing to Press Nexus

## Principles

- Prefer small, verifiable PRs over large batches of changes.
- Define contract and invariants first, implementation second.
- Do not add functionality without tests and risk rationale.
- Any production logic change must remain observable (logs/metrics).

## Environment Requirements

- JDK 21 (`javac` is required)
- Docker (for local infrastructure when needed)
- Git
- Ensure JDK 21 is available via `JAVA_HOME` or `PATH` for host-side Maven commands.

## Workflow

1. Create a branch: `feature/<short-topic>` or `fix/<short-topic>`.
2. Start reproducible local environment with `docker compose -f docker/compose.yml up -d app`.
3. Implement the change within a limited scope.
4. Update documentation if behavior/config/contracts changed.
5. Run local verification:
```bash
./mvnw -B -ntp clean verify
```
For contract changes, run separately:
```bash
./mvnw -B -ntp -pl app -Dtest='*ContractTest' test
```
Breaking contract changes are guarded by the PR CI job `contract-breaking`.
6. Open a PR using the template and complete all required sections.

## Definition of Done (minimum)

A change is considered complete only if:

- `./mvnw clean verify` succeeds.
- CI pipeline passes.
- Tests are included, or an explicit risk-based exception is documented.
- docs/ADR are updated when architecture decisions change.
- Public contracts are not broken (or version bump is applied).
- For API/event changes, `docs/contracts/*` and `*ContractTest` are updated.

Full version: `docs/definition-of-done.md`.

## Commits

- Style: imperative mood, up to 72 chars in subject.
- Conventional Commits are preferred:
  - `feat(news): add source health scoring`
  - `fix(queue): avoid duplicate enqueue on retry`
  - `docs(adr): document ingestion idempotency`

## Pull Request

- One PR = one goal.
- Required in description: `what`, `why`, `risk`, `test evidence`, `rollback plan`.
- If migrations/operational changes are included, add a runbook section.
- Reference good PR example: `docs/examples/good-pr.md`.

## What Is Not Accepted

- Failing CI.
- Changes without ownership or clear rationale.
- Secrets and sensitive data in the repository.
- Large refactors without decomposition and ADR.
