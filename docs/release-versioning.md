# Release and Versioning Policy

## SemVer

- `MAJOR`: breaking API/event contract changes.
- `MINOR`: backward-compatible features.
- `PATCH`: backward-compatible fixes.

## Contract Versioning

- HTTP contract source of truth: `docs/contracts/openapi.yaml`.
- Event contract source of truth: `docs/contracts/events/*.schema.json`.
- Rules are defined in `docs/contracts/versioning-policy.md`.

## Release Checklist

1. Freeze scope.
2. Run full verify and ensure CI is green.
3. Confirm changelog and deprecation notes.
4. Validate DB migration and rollback strategy.
5. Tag release.

## Compatibility Policy

- No public contract changes without version bump.
- Deprecated contract elements stay for at least one MINOR cycle before removal.

