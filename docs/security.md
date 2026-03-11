# Security Policy

## Baseline Rules

- No secrets in repository.
- Use env vars or secret manager only.
- Mask sensitive data in logs.
- Least privilege for DB and external APIs.
- Internal operational endpoints must be protected with `X-PressNexus-Api-Key` when `press.security.internal-api.enabled=true`.
- Telegram webhook ingress should validate `X-Telegram-Bot-Api-Secret-Token`.
- Production CD authenticates with `PROD_SSH_KEY`.
- Production CD passes deploy-time application secrets from GitHub Actions to the remote `docker compose` command instead of storing a persistent `.env` on the server.
- Production deployments should be gated by the GitHub `production` environment instead of relying on repository-wide secrets alone.

## Dependency Hygiene

- Dependabot enabled for Maven and GitHub Actions.
- Critical CVEs must be fixed with highest priority.

## Security Scanning

- Maven quality gates and tests remain on the blocking CI path.
- Additional secret/dependency/SAST scanning can be enabled separately, but should not make the default delivery path flaky.

## Threat Modeling

- Baseline threat model is documented in `docs/threat-model.md`.
- Any architecture change touching trust boundaries must update threat model.

## Incident Reporting

Do not disclose exploit details in public issues. Report privately to repository owner.
