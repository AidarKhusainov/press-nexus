# Press Nexus

Press Nexus aggregates news from open sources, normalizes and enriches content, builds AI embeddings, and produces concise briefs/digests.

## Quality Goals

- Predictable changes through a standardized PR process.
- Merge blocked when quality gates fail.
- Documented architecture decisions and invariants.
- Observability and operational runbooks by default.

## Technology Stack

- Java 21 (JDK required, not just JRE)
- Spring Boot 3.x, WebFlux, R2DBC, Liquibase
- Maven Wrapper (`./mvnw`)
- PostgreSQL
- Ollama (local/external) for AI features
- Prometheus + Grafana + Loki for observability

## Repository Structure

- `app/` — core application module
- `docs/` — engineering documentation (architecture, DoD, NFR, ADR, runbooks)
- `.github/` — CI, PR/Issue templates, CODEOWNERS
- `monitoring/` — Prometheus/Grafana/Loki/Promtail configs
- `scripts/` — local verification utilities

## Quick Start

Set required DB password via environment before starting local services:

```bash
export PRESS_DB_PASSWORD='<set-db-password>'
```

If you use monitoring stack, also set:

```bash
export GRAFANA_ADMIN_PASSWORD='<set-grafana-password>'
```

1. Check environment:
```bash
./scripts/use-jdk21.sh
```
2. Full verification:
```bash
./scripts/use-jdk21.sh ./mvnw -B -ntp clean verify
```
3. Run application:
```bash
./scripts/use-jdk21.sh ./mvnw -pl app spring-boot:run
```
4. Start local environment (DB + AI):
```bash
./scripts/init-local-env.sh
```

## Development Commands

- Build + tests: `./mvnw clean verify`
- Tests only: `./mvnw -pl app test`
- Generate HTTP controllers from OpenAPI: `./scripts/generate-openapi.sh`
- Fast local check: `./scripts/verify-local.sh`
- Start monitoring stack:
```bash
docker compose --profile monitoring up -d prometheus grafana loki promtail
```

## Quality Gates (Mandatory)

- Maven Enforcer: Java/Maven versions and build consistency.
- ArchUnit: architecture boundaries between layers.
- Unit/Integration tests and Surefire/Failsafe reports.
- Contract tests + breaking-change guard for `docs/contracts/*`.
- JaCoCo: minimum line coverage threshold per module.
- Gitleaks + CodeQL + SBOM (CycloneDX) + Grype scan.
- GitHub Actions CI workflow: merge is blocked without green pipeline.

## Operational Links

- Actuator: `http://localhost:8080/actuator`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`
- Prometheus UI: `http://localhost:9090`
- Grafana UI: `http://localhost:3000` (`admin` / value from `GRAFANA_ADMIN_PASSWORD`)
- Loki API: `http://localhost:3100`

## Engineering Policies

- Contribution process: [CONTRIBUTING.md](CONTRIBUTING.md)
- Rules for humans and AI agents: [AGENTS.md](AGENTS.md)
- Architecture: [docs/architecture.md](docs/architecture.md)
- Definition of Done: [docs/definition-of-done.md](docs/definition-of-done.md)
- Non-functional requirements: [docs/non-functional-requirements.md](docs/non-functional-requirements.md)
- Quality gates: [docs/quality-gates.md](docs/quality-gates.md)
- ADR index: [docs/adr/README.md](docs/adr/README.md)
- API/Event contracts: [docs/contracts/README.md](docs/contracts/README.md)
- Contract versioning: [docs/contracts/versioning-policy.md](docs/contracts/versioning-policy.md)
- Security policy: [docs/security.md](docs/security.md)
- Threat model: [docs/threat-model.md](docs/threat-model.md)
- Observability baseline: [docs/observability.md](docs/observability.md)
- Refactoring policy: [docs/refactoring-policy.md](docs/refactoring-policy.md)
- Local environment runbook: [docs/runbooks/local-dev-environment.md](docs/runbooks/local-dev-environment.md)
- Good PR example: [docs/examples/good-pr.md](docs/examples/good-pr.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)

## Current Product State

- MVP tracker: `docs/MVP_PROGRESS.md`
- Product/context knowledge base: `docs/AI_KB.md`
