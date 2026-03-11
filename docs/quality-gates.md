# Quality Gates

## Mandatory Gates

- Maven Enforcer:
  - Java >= 21
  - Maven >= 3.8.7
  - duplicate dependency ban
  - explicit plugin versions
- Formatter check: Spotless (`spotless:check`)
- Lint: Checkstyle (`checkstyle:check`)
- Static analysis: SpotBugs (`spotbugs:check`)
- Architecture rules: ArchUnit (`ArchitectureRulesTest`)
- Unit tests: Surefire
- Contract tests: `*ContractTest`
- Contract breaking guard: CI job `contract-breaking` (PR)
- Integration tests: Failsafe profile
- Coverage threshold: JaCoCo line coverage >= configured minimum
- Secrets scanning: Gitleaks
- SAST: CodeQL
- Dependency risk gate: Dependency Review
- SBOM generation: CycloneDX (`target/bom.json`)
- Vulnerability scan: Grype (SBOM-based)
- CI status: green for all required jobs

## Merge Policy

Merge to protected branch is blocked if any gate fails.

## Local Run

```bash
./mvnw -B -ntp clean verify
```

## Explicit Contract Tests Run

```bash
./mvnw -B -ntp -pl app -Dtest='*ContractTest' test
```

## Contract Breaking Check (PR)

Handled by the GitHub Actions `contract-breaking` job on pull requests.

## Integration Profile

```bash
./mvnw -pl app -P integration-tests -Ddb.tests=true verify
```

## Live Profile (manual only)

```bash
./mvnw -pl app -P live-tests -Dlive.tests.coverage=true verify
```
