# Architecture Overview

## Context

Press Nexus implements an ingest-enrich-summarize pipeline for news content, followed by personalized delivery.

## Module Boundaries

- `service/news` — ingest/normalize/dedup/persist/similarity pipeline.
- `service/queue` — in-process queues and consumer orchestration.
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
2. Content parse/populate
3. Persist/upsert
4. Embedding + similarity
5. Summarization
6. Clustering/brief/delivery

## Technical Invariants

- External HTTP calls: timeout + retry + metrics + contextual logging.
- Queues and scheduler: each task must be safe for re-run.
- DB migrations: forward-compatible only (rollback via a new migration).
- Logs: structured JSON, no secrets or PII.
- Public API/event contracts: source of truth in `docs/contracts/`; breaking changes only via version bump.

## Decisions and Changes

All architecture-level decisions are captured via ADRs in `docs/adr/`.
