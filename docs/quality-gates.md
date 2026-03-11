# Quality Gates

## Mandatory Gates

- Maven Enforcer
- Formatter check: Spotless (`spotless:check`)
- Lint: Checkstyle (`checkstyle:check`)
- Static analysis: SpotBugs (`spotbugs:check`)
- Architecture rules: ArchUnit (`ArchitectureRulesTest`)
- Unit, contract, and architecture tests
- Integration tests: enabled in CI via `-Ddb.tests=true`
- Live tests: optional, excluded from mandatory CI
- Coverage threshold: JaCoCo line coverage >= configured minimum
- CI workflow `verify`: green
- Image publish on successful push to `master`

## Merge Policy

Merge to protected branch is blocked if any gate fails.

## Local Run

```bash
./mvnw -B -ntp clean verify
```

## Full CI Run

```bash
./mvnw -B -ntp clean verify -Ddb.tests=true
```

## Out Of Critical Path

- Dependabot remains enabled for dependency updates.
- Extra security scans may be run manually or added later, but they are not blocking the default delivery path.
