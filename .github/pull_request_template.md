## Summary

- What changed?
- Why is this change needed?

## Scope

- [ ] Feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Docs
- [ ] Infra/CI

## Risk Assessment

- Risk level: `low | medium | high`
- Main risk:
- Rollback plan:

## Test Evidence

- [ ] `./mvnw clean verify` passed locally
- [ ] New/updated tests included
- [ ] CI is green
- [ ] Manual checks attached (if needed)

## Contract & Data Impact

- [ ] No public API/event contract changes
- [ ] Contract changed and versioning/backward-compatibility is handled
- [ ] Contract files updated (`docs/contracts/openapi.yaml`, `docs/contracts/events/*`)
- [ ] Contract tests updated (`*ContractTest`)
- [ ] Contract breaking check passed (`scripts/check-contract-breaking.sh`)
- [ ] No DB migration
- [ ] DB migration included + rollback/mitigation documented

## Observability

- [ ] Metrics/logging updated for new critical path
- [ ] Existing dashboards/alerts are still valid

## Documentation

- [ ] README/CONTRIBUTING updated (if behavior changed)
- [ ] ADR added/updated (if architecture changed)
- [ ] Runbook updated (if operational behavior changed)

## Checklist

- [ ] Change is small and focused
- [ ] No secrets or sensitive data added
- [ ] Security scans passed (Gitleaks / CodeQL / dependency review)
- [ ] Definition of Done is satisfied
