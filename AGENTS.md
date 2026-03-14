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

### B. If event/webhook payload changes

- Update `docs/contracts/events/*.schema.json`.
- For breaking change: add a new versioned file `-vN`.
- Update contract tests.
- Run mandatory CI and contract tests.

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

If DB-backed integration behavior is impacted:

2. `./mvnw -B -ntp clean verify -Ddb.tests=true`

If contracts are impacted:

3. `./mvnw -B -ntp -pl app -Dtest='*ContractTest' test`

## 8) CI Gates (must stay green)

- Maven Enforcer
- Checkstyle
- SpotBugs
- Spotless
- JaCoCo threshold
- Unit/contract tests
- ArchUnit tests
- DB-backed integration tests in CI
- Container image publish on `master`

The agent must not treat a task as complete if any mandatory gate is expected to fail.

## 9) Coding Conventions

- Language: Java 21, UTF-8.
- Java indentation: tabs.
- Package root: `com.nexus.press.app`.
- Naming: classes `PascalCase`, methods/fields `camelCase`.
- Prefer constructor injection (`@RequiredArgsConstructor`).
- Avoid noisy refactors outside task scope.

## 10) Design And Architecture Guardrails

- Prefer simple, explicit, readable code over clever or overly compressed code.
- Keep code ownership clear: business rules belong in business services, infrastructure rules belong in infrastructure layers.
- Shared infrastructure must stay generic. `config`, `http`, `db`, `messaging`, schedulers, and common support code must not contain provider-specific or feature-specific branching when that logic can live in the owning service.
- Do not hardcode special cases such as `if client == GEMINI`, `switch(provider)` in unrelated layers, or name/enum-based branching as a substitute for proper abstractions.
- If behavior differs by provider/integration/feature, prefer one of:
  - provider-owned logic in the provider implementation
  - per-client or per-feature configuration
  - injected strategy/policy interfaces
- Configuration and composition are preferred over conditional branching in shared code.
- Preserve architectural boundaries. Do not solve a local feature problem by increasing coupling between unrelated modules.
- Before changing a shared layer, first ask whether the decision can be pushed one layer closer to the real owner of the behavior.
- Tactical fixes in shared code are not acceptable if a cleaner design is reasonable within the current task scope.
- If a tactical shortcut is truly necessary, it must be:
  - explicitly called out as temporary technical debt
  - justified in the final response
  - localized so future cleanup is small
- Avoid “enum-driven architecture”: cross-layer `switch`/`if` trees on types, providers, channels, or client names are a code smell unless the enum is the natural owner of the behavior.
- New code should reduce ambiguity, not add it:
  - method names should match real behavior
  - responsibilities should be narrow
  - side effects should be easy to see
  - hidden coupling should be avoided
- Keep APIs and flows unsurprising. A generic component must not silently encode product policy.
- Prefer designs that remain easy to extend without editing existing unrelated code paths.

## 11) Code Cleanliness Bar

- New code must be easy to review quickly: low nesting, clear naming, minimal incidental complexity.
- Avoid speculative abstractions, but also avoid copy-pasted policy logic spread across multiple classes.
- Do not introduce “temporary” hacks, magic constants, or ad hoc conditionals without naming and encapsulating them properly.
- Comments must explain non-obvious intent, not compensate for confusing structure.
- Error handling must preserve correctness first; do not hide bugs behind broad fallbacks unless that degraded path is explicitly required.
- When multiple correct implementations exist, choose the one with lower coupling, clearer boundaries, and easier testability.
- Tests should verify behavior at the layer where the rule lives. Do not rely only on indirect coverage for important policy decisions.

## 12) Safety Rules For Agents

- Change only files relevant to the task.
- Do not remove working functionality without explicit request.
- If unexpected changes are found in working tree, stop and align.
- Do not revert other people’s changes without direct user instruction.

## 13) Definition Of Done For Agent Response

In the final response, the agent must provide:

- What was implemented (against user requirements).
- Which files were changed.
- Which checks were run and their results.
- What remains/limitations (if any).
- Risks and rollback considerations (if applicable).

## 14) Helpful Commands

- Full local verify: `./mvnw -B -ntp clean verify`
- Full CI-equivalent verify: `./mvnw -B -ntp clean verify -Ddb.tests=true`
- Regenerate OpenAPI HTTP controllers: `./mvnw -B -ntp -pl app generate-sources`
- Contract tests only: `./mvnw -B -ntp -pl app -Dtest='*ContractTest' test`
- Start local infra: `docker compose -f docker/compose.yml up -d app`
