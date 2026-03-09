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
- Contract breaking guard: `scripts/check-contract-breaking.sh` (PR)
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
./scripts/verify-local.sh
```

## Explicit Contract Tests Run

```bash
./scripts/verify-contracts.sh
```

## Contract Breaking Check (PR)

```bash
CONTRACT_BASE_REF=origin/main ./scripts/check-contract-breaking.sh
```

## Integration Profile

```bash
./scripts/use-jdk21.sh ./mvnw -pl app -P integration-tests -Ddb.tests=true verify
```

## Live Profile (manual only)

```bash
./scripts/use-jdk21.sh ./mvnw -pl app -P live-tests -Dlive.tests.coverage=true verify
```
