# Security Policy

## Baseline Rules

- No secrets in repository.
- Use env vars or secret manager only.
- Mask sensitive data in logs.
- Least privilege for DB and external APIs.
- Internal operational endpoints must be protected with `X-PressNexus-Api-Key` when `press.security.internal-api.enabled=true`.
- Telegram webhook ingress should validate `X-Telegram-Bot-Api-Secret-Token`.
- Production CD currently authenticates to the server with `PROD_SSH_KEY` and does not verify the SSH host identity in GitHub Actions.

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
