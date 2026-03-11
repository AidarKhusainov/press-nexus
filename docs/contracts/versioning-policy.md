# Contract Versioning Policy

## Scope

- HTTP API contract: `docs/contracts/openapi.yaml`
- Event contracts: `docs/contracts/events/*.schema.json`

## Rules

- Breaking changes require version bump (`vN -> vN+1`) and migration note.
- Non-breaking additions stay in the same major contract version.
- Deprecated fields/endpoints must be announced in changelog before removal.
- PR that changes contract must include:
  - updated contract file(s)
  - contract tests
  - compatibility note in PR template
- PR must pass the mandatory CI workflow and contract tests.

## Examples of breaking changes

- Removing endpoint/field.
- Changing field type.
- Tightening enum/validation in a way that rejects old clients.

## Examples of non-breaking changes

- Adding optional field.
- Adding new endpoint.
- Expanding enum in documented forward-compatible scenario.
