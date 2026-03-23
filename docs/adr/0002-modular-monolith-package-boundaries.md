# ADR 0002: Modular Monolith Package Boundaries and Visibility Rules

- Status: Accepted
- Date: 2026-03-21
- Deciders: Press Nexus maintainers

## Context

The codebase currently groups much of the application under broad root packages such as `service`, `controller`, and `repository`.

This has led to mixed responsibilities in large classes:

- orchestration mixed with SQL
- transport parsing mixed with business flow
- persistence concerns mixed with policy logic

The project also needs a package structure that supports future extraction of bounded areas into separate microservices without rewriting core business logic.

## Decision

Adopt an extractable modular monolith structure.

Key rules:

- top-level modules are `news`, `brief`, `profile`, `telegram`, `analytics`, `feedback`, `premium`, and `ai`
- new code is organized by module first, not by technical layer first
- each module uses focused packages such as `web`, `usecase`, `model`, `policy`, `persistence`, `integration`, `format`, and `job`
- use case classes are concrete classes with exactly one public method
- interfaces are used only for repositories and external integration gateways
- SQL is allowed only in persistence packages
- modules must not depend on another module's persistence package
- legacy root packages `service`, `controller`, and `repository` are migration-only and must not receive new code

## Alternatives Considered

- Keep the current `service/controller/repository` split: rejected because it hides business boundaries and increases extraction cost.
- Adopt full interface-driven architecture for all services: rejected because it adds ceremony without a real boundary.
- Adopt strict hexagonal architecture everywhere: rejected because it is heavier than needed for the current codebase.

## Consequences

- Pros: clearer ownership, smaller classes, easier package-level architecture tests, lower future extraction cost.
- Pros: business orchestration becomes easier to locate and review.
- Cons: refactoring cost while migrating existing classes.
- Cons: some internal classes may remain `public` due to Java package visibility and Spring wiring constraints.

Risks and mitigations:

- Risk: teams keep adding code to legacy root packages.
  Mitigation: enforce the target structure with ArchUnit.
- Risk: module boundaries exist only in docs.
  Mitigation: add architecture tests for allowed package dependencies.

## Follow-up

- Refactor large mixed-responsibility classes toward module-local `usecase`, `policy`, and `persistence` packages.
- Add ArchUnit rules for module boundaries, legacy package freeze, and `DatabaseClient` placement.
- Move scheduled tasks under owning module `job` packages over time.
