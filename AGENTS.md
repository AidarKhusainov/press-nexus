# AGENTS Operating Contract (Press Nexus)

This document is intended to be sufficient for users to simply write:
"add/fix <feature>".
The agent must execute the full engineering lifecycle according to the rules below.

## 1) Default Mode (if the user does not specify details)

- Work end-to-end: analysis -> implementation -> tests -> documentation -> result.
- Make the minimal necessary change without side refactors.
- Preserve backward compatibility of public contracts by default.
- When ambiguous, choose the safer/backward-compatible option.
- Do not ask the user for process steps already defined by this file.

## 2) Repository Map

- Root: Maven multi-module build (`pom.xml`), current module: `app`.
- Source code: `app/src/main/java/com/nexus/press/app/**`.
- Resources: `app/src/main/resources/**`.
- Tests: `app/src/test/java/**`.
- Contracts: `docs/contracts/**`.
- Architecture decisions: `docs/adr/**`.
- Engineering docs: `docs/**`.
- CI and templates: `.github/**`.

## 3) Mandatory Runtime/Toolchain

- Java: only JDK 21.
- JDK 21 must be available via `JAVA_HOME` or `PATH` for host-side Maven commands.
- Standard commands:
  - `./mvnw -B -ntp clean verify`
  - `./mvnw -pl app spring-boot:run`

## 4) Non-Negotiable Invariants

- Do not break public API/event contracts without version bump and migration note.
- Do not introduce silent behavior changes: breaking changes must be explicit and justified.
- HTTP controllers are contract-first and generated from `docs/contracts/openapi.yaml`.
- Do not hand-write or manually edit generated API controllers/interfaces/models.
- OpenAPI models (DTO) must also be generated from spec; do not map them to domain models via generator `importMappings`.
- Any new business logic must have test coverage.
- Any bug fix must include a regression test.
- Any external call must include timeout + retry/backoff + observability.
- Never store secrets in the repository.
- Do not perform unapproved destructive data operations.

## 5) Change Protocol (mandatory work order)

1. Understand the user request and constrain scope.
2. Identify affected layers: `web/service/repository/contracts/docs`.
3. Implement the change with minimal patch.
4. Update tests at the correct levels.
5. Update contracts/documentation if impacted.
6. Run mandatory checks.
7. Confirm DoD is satisfied.
8. Return result to user with risks and changed files list.

## 6) What To Update By Change Type

### A. If HTTP endpoint or response/error format changes

- Update `docs/contracts/openapi.yaml`.
- Regenerate and compile generated APIs/controllers:
  - `./mvnw -B -ntp -pl app generate-sources`
- Update/add `*ContractTest`.
- Run:
  - `./mvnw -B -ntp -pl app -Dtest='*ContractTest' test`
  - pull request CI job `contract-breaking`

### B. If event/webhook payload changes

- Update `docs/contracts/events/*.schema.json`.
- For breaking change: add a new versioned file `-vN`.
- Update contract tests.
- Run contract-breaking check.

### C. If architecture/module boundaries change

- Update or add ADR in `docs/adr/*.md`.
- Ensure ArchUnit rules remain valid.

### D. If operational behavior changes (timeouts, retries, limits, SLO)

- Update corresponding sections in:
  - `docs/non-functional-requirements.md`
  - `docs/observability.md`
  - `docs/runbooks/*` (if needed)

### E. If security posture changes

- Update `docs/security.md` and, when needed, `docs/threat-model.md`.

## 7) Mandatory Checks Before “Done”

Minimum for any functional task:

1. `./mvnw -B -ntp clean verify`

If contracts are impacted:

2. `./mvnw -B -ntp -pl app -Dtest='*ContractTest' test`
3. PR CI `contract-breaking` job must pass

## 8) CI Gates (must stay green)

- Maven Enforcer
- Checkstyle
- SpotBugs
- Spotless
- JaCoCo threshold
- Unit/contract tests
- ArchUnit tests
- Dependency Review
- Gitleaks
- SBOM (CycloneDX) + Grype vuln scan
- CodeQL (separate workflow)

The agent must not treat a task as complete if any mandatory gate is expected to fail.

## 9) Coding Conventions

- Language: Java 21, UTF-8.
- Java indentation: tabs.
- Package root: `com.nexus.press.app`.
- Naming: classes `PascalCase`, methods/fields `camelCase`.
- Prefer constructor injection (`@RequiredArgsConstructor`).
- Avoid noisy refactors outside task scope.

## 10) Safety Rules For Agents

- Change only files relevant to the task.
- Do not remove working functionality without explicit request.
- If unexpected changes are found in working tree, stop and align.
- Do not revert other people’s changes without direct user instruction.

## 11) Definition Of Done For Agent Response

In the final response, the agent must provide:

- What was implemented (against user requirements).
- Which files were changed.
- Which checks were run and their results.
- What remains/limitations (if any).
- Risks and rollback considerations (if applicable).

## 12) Helpful Commands

- Full local verify: `./mvnw -B -ntp clean verify`
- Regenerate OpenAPI HTTP controllers: `./mvnw -B -ntp -pl app generate-sources`
- Contract tests only: `./mvnw -B -ntp -pl app -Dtest='*ContractTest' test`
- Contract breaking guard: PR CI job `contract-breaking`
- Start local infra: `docker compose -f docker/compose.yml up -d app`
