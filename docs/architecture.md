# Architecture Overview

## Context

Press Nexus implements an ingest-enrich-summarize pipeline for news content, followed by personalized delivery.

## Module Boundaries

- `service/news` — ingest/normalize/dedup/persist/similarity pipeline.
- `service/brief`, `service/delivery`, `service/profile` — user value creation and delivery.
- `service/analytics`, `observability` — product and technical metrics.
- `repository` — data access.
- `web` — HTTP/webhook interfaces.

## Core Principles

- Domain-first: business flow is more important than framework details.
- Explicit boundaries: dependencies must point from API to implementation, not vice versa.
- Fail fast + observable: errors are not hidden; every critical operation is measured.
- Idempotency by design: repeated processing must not create duplicates or corrupt state.

## Data Flow (simplified)

1. Source fetch
2. Discovery upsert into PostgreSQL backlog
3. DB-claimed content populate
4. DB-claimed embedding + pgvector persist
5. DB-claimed clustering + summarization
6. Brief/delivery

## Technical Invariants

- External HTTP calls: timeout + retry + metrics + contextual logging.
- Pipeline workers use DB-backed claim/lease semantics (`status_*` + stage-specific `*_claimed_at`) instead of in-memory stage queues.
- Scheduler and workers: each run must be safe for re-run and must not overlap with itself.
- DB migrations: forward-compatible only (rollback via a new migration).
- Logs: structured JSON, no secrets or PII.
- Public API/event contracts: source of truth in `docs/contracts/`; breaking changes only via version bump.

## Decisions and Changes

All architecture-level decisions are captured via ADRs in `docs/adr/`.
