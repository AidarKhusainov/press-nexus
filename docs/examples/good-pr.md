# Good PR Example

## Title

`feat(contracts): add webhook contract test and versioned schema`

## What

- Added `docs/contracts/events/telegram-webhook-update-v1.schema.json`.
- Added `ApiContractTest` case for `/api/telegram/webhook`.
- Updated contract versioning policy doc.

## Why

- Prevent silent contract drift.
- Keep webhook behavior reproducible across changes.

## Risk

- Low. No behavior change in production logic.

## Test Evidence

- `./mvnw -B -ntp clean verify` passed.
- `./mvnw -pl app -Dtest='*ContractTest' test` passed.

## Rollback

- Revert PR commit; no data migration impact.
