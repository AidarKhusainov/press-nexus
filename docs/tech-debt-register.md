# Tech Debt Register

## Format

Each debt item includes:

- ID
- Description
- Risk
- Owner
- Target date
- Exit criteria
- Status

## Items

| ID | Description | Risk | Owner | Target date | Exit criteria | Status |
|---|---|---|---|---|---|---|
| TD-001 | Move in-process queues to external broker with DLQ | High | @aidar | 2026-06-30 | Broker integrated, DLQ + retry policy + migration runbook ready | Open |
| TD-002 | Harden API/event contract testing | Medium | @aidar | 2026-05-31 | Contract tests cover all public endpoints and event schemas | In Progress |
| TD-003 | Automate D1/D7 quality reporting in dashboards | Medium | @aidar | 2026-04-30 | Daily report and dashboard alerts are generated automatically | Open |

