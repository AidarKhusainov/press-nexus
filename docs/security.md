# Security Policy

## Baseline Rules

- No secrets in repository.
- Use env vars or secret manager only.
- Mask sensitive data in logs.
- Least privilege for DB and external APIs.

## Dependency Hygiene

- Dependabot enabled for Maven and GitHub Actions.
- Dependency review check is mandatory for PR.
- CycloneDX SBOM is generated in CI (`target/bom.json`).
- SBOM is scanned with Grype; high-severity findings fail the pipeline.
- Critical CVEs must be fixed with highest priority.

## Security Scanning

- Gitleaks is mandatory in CI to prevent secret leakage.
- CodeQL SAST is enabled for Java code.

## Threat Modeling

- Baseline threat model is documented in `docs/threat-model.md`.
- Any architecture change touching trust boundaries must update threat model.

## Incident Reporting

Do not disclose exploit details in public issues. Report privately to repository owner.
