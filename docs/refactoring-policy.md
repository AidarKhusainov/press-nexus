# Refactoring Policy

## Rules

- Leave code cleaner than you found it.
- Prefer small, focused PRs.
- No big-bang rewrites in one PR.
- Preserve behavior first, then refactor.
- Any risky refactor requires rollback strategy.

## Mandatory Practices

- Add regression tests before refactoring critical logic.
- Keep public contracts backward-compatible unless versioned.
- Update ADR if architecture-level decisions change.

