# ADR 0001: Engineering Quality Gates as Merge Baseline

- Status: Accepted
- Date: 2026-03-09
- Deciders: Press Nexus maintainers

## Context

The project is evolving rapidly and has a high risk of quality degradation under fast iterations. A mandatory, automatable quality framework is required.

## Decision

Adopt quality gates as the mandatory baseline:

- Maven Wrapper as a single entry point for build execution.
- Maven Enforcer for Java/Maven version and consistency checks.
- JaCoCo coverage check at module level.
- CI-blocking policy: merge only with a green pipeline.
- Mandatory PR/Issue templates + DoD + ADR process.

## Alternatives Considered

- Manual review without automated gates: rejected due to human-factor risk.
- Full static analysis suite from day one (SpotBugs/Checkstyle/PMD): postponed to next iteration after baseline gates stabilize.

## Consequences

- Pros: fewer regressions, more predictable releases, transparent standards.
- Cons: slightly higher PR cost, ongoing documentation and CI maintenance.

## Follow-up

- Add SpotBugs/PMD/Checkstyle after coverage threshold stabilizes.
- Automate NFR/SLO conformance checks in pipeline.
