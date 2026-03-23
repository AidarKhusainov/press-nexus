# Architecture

## Goal

Press Nexus uses a modular monolith architecture with explicit extraction seams for future microservices.

The target state is:

- module-first package structure
- no technical `service/repository/controller` root split for new code
- one public method per use case class
- no interfaces for internal services
- interfaces only at real boundaries: repositories and external integrations
- SQL isolated in persistence, not in use case classes
- module boundaries enforced by package rules and ArchUnit

This document defines the target package layout, visibility rules, dependency rules, and naming rules.

## Architectural Style

Press Nexus is an extractable modular monolith.

This means:

- modules are separated inside one codebase and one process
- each module owns its business logic and persistence access
- modules communicate through small public entry points
- modules do not read or write each other's persistence directly
- future microservice extraction should replace a module boundary, not rewrite its core logic

The architecture is intentionally pragmatic:

- no interfaces for internal application services
- no `FooService` + `FooServiceImpl` pairs
- no hexagonal ceremony where it does not buy a real boundary

## Top-Level Package Layout

New code should move toward the following structure under `app/src/main/java/com/nexus/press/app/`:

```text
com.nexus.press.app
  AppApplication
  config/
  observability/
  shared/
  news/
  brief/
  profile/
  telegram/
  analytics/
  feedback/
  premium/
  ai/
```

Meaning:

- `config/` - Spring Boot bootstrap, shared infrastructure configuration, security filters, WebClient wiring, property classes
- `observability/` - metrics, common instrumentation support
- `shared/` - low-level generic helpers only; no business rules
- `news/`, `brief/`, `profile/`, `telegram/`, `analytics/`, `feedback/`, `premium/` - business modules
- `ai/` - shared AI provider module for embeddings and summarization integrations

Legacy root packages:

- `controller/`
- `service/`
- `repository/`

are transitional only. New code should not be added there except during migration of existing classes.

## Module Package Layout

Each business module should use the same internal shape where relevant:

```text
<module>/
  web/
  usecase/
  model/
  policy/
  persistence/
    repository/
    query/
    entity/
    mapper/
  integration/
  format/
  job/
```

Not every module must contain every subpackage. Only create packages that have real content.

Package meaning:

- `web/` - HTTP/webhook adapters, transport mapping, controller delegates
- `usecase/` - orchestration classes, one class per use case, one public method
- `model/` - module-owned business records, enums, value objects; no persistence annotations
- `policy/` - pure business rules, ranking, validation, scheduling, selection logic
- `persistence/repository/` - repository interfaces plus persistence adapters that load/store module state
- `persistence/query/` - read-only SQL selectors, report queries, claim/lease queries, projection queries
- `persistence/entity/` - persistence-only entities or row-backed data structures
- `persistence/mapper/` - row/entity mappers when they become non-trivial
- `integration/` - external systems and provider adapters
- `format/` - text/message formatting and parsing helpers
- `job/` - scheduled triggers and background entrypoints that invoke use cases

## Module Ownership

### `news`

Owns:

- source discovery
- content population
- content cleaning
- embedding
- similarity storage
- clustering
- summarization state
- pipeline claim/lease mechanics

Typical classes:

- `usecase/FetchNewsBatch`
- `usecase/PopulateNewsContent`
- `usecase/EmbedNewsBatch`
- `usecase/SummarizeNews`
- `usecase/RunNewsIngestionCycle`
- `policy/SummaryBudgetPolicy`
- `policy/ClusterSelectionPolicy`
- `persistence/query/ClaimNewsForContentQuery`
- `persistence/query/ClaimNewsForSummaryQuery`
- `persistence/repository/NewsRepository`
- `integration/rss/*`
- `integration/content/*`

### `ai`

Owns:

- embedding provider adapters
- summarization provider adapters
- prompt construction and provider response parsing
- provider routing and fallback policy

Typical classes:

- `integration/embed/OllamaNomicEmbeddingService`
- `integration/summ/GeminiSummarizationService`
- `integration/summ/ConfiguredSummarizationService`
- `model/SummarizationProvider`
- `model/SummarizationUseCase`

### `brief`

Owns:

- brief candidate loading
- deduplication
- importance ranking
- topic filtering
- brief assembly
- brief formatting

Typical classes:

- `usecase/BuildDailyBrief`
- `policy/BriefRankingPolicy`
- `policy/NearDuplicatePolicy`
- `policy/TopicFilterPolicy`
- `persistence/query/BriefCandidateQuery`
- `format/DailyBriefFormatter`

### `profile`

Owns:

- users
- topics
- digest frequency
- digest eligibility rules
- subscription state

Typical classes:

- `usecase/RegisterTelegramUser`
- `usecase/UpdateUserTopics`
- `usecase/UpdateDigestFrequency`
- `usecase/FindUsersDueForDigest`
- `usecase/MarkDigestDelivered`
- `policy/DigestSchedulePolicy`
- `policy/TopicSelectionPolicy`
- `persistence/repository/UserProfileRepository`

### `telegram`

Owns:

- Telegram webhook handling
- Telegram update parsing
- command and callback routing
- message rendering and keyboards
- Telegram transport
- Telegram-specific delivery orchestration

Typical classes:

- `web/TelegramWebhookController`
- `usecase/HandleTelegramUpdate`
- `usecase/HandleTelegramCommand`
- `usecase/HandleTelegramCallback`
- `usecase/DeliverDailyBriefToTelegramUsers`
- `format/TelegramMessageFactory`
- `format/TelegramKeyboardFactory`
- `format/TelegramUpdateParser`
- `integration/TelegramBotGateway`

### `analytics`

Owns:

- product report generation
- analytics-specific aggregations
- analytics read models and reporting queries

Typical classes:

- `usecase/BuildProductDailyReport`
- `persistence/query/ProductReportQuery`
- `policy/PremiumSegmentPolicy`
- `format/ProductReportFormatter`

### `feedback`

Owns:

- feedback event types and callback parsing
- feedback event ingestion through HTTP and Telegram
- feedback event persistence

Typical classes:

- `web/FeedbackController`
- `usecase/RecordTelegramFeedback`
- `format/TelegramFeedbackCallbackData`
- `persistence/repository/FeedbackEventRepository`

### `premium`

Owns:

- premium intent callback parsing
- premium audience segmentation rules
- premium intent event persistence

Typical classes:

- `format/PremiumIntentCallbackData`
- `policy/PremiumSegmentResolver`
- `usecase/RecordTelegramPremiumIntent`
- `persistence/repository/PremiumIntentEventRepository`

### `shared`

Allowed:

- generic text helpers
- generic vector helpers
- generic clock/time helpers
- generic JSON helpers

Not allowed:

- business policy
- provider-specific branching
- module-specific SQL
- module-specific DTOs

## Public Surface and Visibility

### Public by default

The following classes may be public:

- `web` entrypoints
- `usecase` entrypoints
- repository interfaces
- external gateway interfaces
- immutable models that are intentionally shared across module boundaries
- configuration classes required by Spring

### Internal by default

The following should be internal to the owning module:

- `policy` classes
- `format` helpers
- query classes
- mapper classes
- repository implementations
- gateway implementations
- persistence entities
- support helpers

### Java-specific rule

Java package-private visibility only works inside one exact package, not across subpackages.

Because of this:

- use package-private where it is practical
- when a class must be `public` due to package structure or Spring wiring, it is still considered internal by architecture rules
- internal `public` classes must not be imported from another module
- this restriction must be enforced with package conventions and ArchUnit, not only with Java visibility

## Dependency Rules

Allowed dependency direction inside one module:

- `web -> usecase`
- `job -> usecase`
- `usecase -> model`
- `usecase -> policy`
- `usecase -> persistence/repository`
- `usecase -> persistence/query`
- `usecase -> integration`
- `persistence -> model`
- `policy -> model`
- `format -> model`

Forbidden dependencies:

- `policy -> persistence`
- `policy -> integration`
- `policy -> web`
- `model -> web`
- `model -> persistence`
- `web -> persistence`
- `job -> persistence`
- one module -> another module's `persistence/*`
- one module -> another module's `integration/*`
- one module -> another module's `format/*`
- one module -> another module's internal helpers

Cross-module calls are allowed only from `usecase` classes and only to another module's public use case or intentionally shared model.

Cross-module calls are not allowed from:

- `policy`
- `persistence`
- `format`
- `integration`

## Interfaces Policy

Interfaces exist only for real boundaries.

Allowed interface categories:

- repository interfaces
- external integration gateways or clients

Examples:

- `NewsRepository`
- `UserProfileRepository`
- `TelegramBotGateway`
- `SummarizationGateway`
- `EmbeddingGateway`

Disallowed patterns:

- `FooService` + `FooServiceImpl` for internal use cases
- local module-to-module `*Client` abstractions created only for hypothetical future extraction
- interface-first design without an actual boundary

Rule:

- while modules live in one process, one module may call another module's public use case directly
- if a module is extracted later, a remote client is introduced at extraction time, not preemptively

## Use Case Rules

Each use case class must:

- represent one business action or one business query
- expose exactly one public method
- use constructor injection
- avoid direct SQL and WebClient calls
- avoid parsing HTTP or Telegram payloads
- keep orchestration in the use case and rules in policies

Method naming:

- default: `execute(...)`
- transport/event handlers may use `handle(...)`

Naming:

- use case classes should use verb-first names

Examples:

- `BuildDailyBrief`
- `RegisterTelegramUser`
- `UpdateUserTopics`
- `HandleTelegramUpdate`
- `RunNewsIngestionCycle`
- `BuildProductDailyReport`

## SQL Placement Rules

SQL is a persistence concern.

Rules:

- `DatabaseClient` must live only in `persistence/repository` or `persistence/query`
- `usecase`, `policy`, `web`, and `job` classes must not contain SQL
- complex `SELECT`, aggregation, claim/lease, and report SQL belongs in `persistence/query`
- writes and aggregate persistence belong in `persistence/repository`
- persistence row mapping belongs in `persistence/mapper` when it is non-trivial

This means:

- no more large SQL-heavy application services
- no `select` logic inside use case classes
- no mixed "service + SQL + policy" classes

## Naming Rules by Responsibility

- use case: verb-first class, one public method
- policy: `...Policy`, `...Selector`, `...Scorer`, `...Validator`
- repository interface: `...Repository`
- query class: `...Query`
- gateway interface: `...Gateway`
- formatter/parser: `...Formatter`, `...Parser`, `...Factory`
- scheduled trigger: `...Job`

Avoid generic names such as:

- `CommonService`
- `HelperService`
- `UtilsService`
- `Manager`

## Inter-Module Data Rules

Rules:

- modules own their business data and persistence access
- one module must not read another module's tables directly
- one module must not expose persistence entities as public contracts
- cross-module data must use module-owned immutable models or dedicated use case input/output records

For future extraction:

- seams must be created at public use case boundaries
- persistence contracts must stay private to the module
- read-heavy consumers should prefer dedicated query models or projections over direct foreign-table access

This is especially important for:

- `brief` reading from `news`
- `telegram` orchestrating `brief` and `profile`
- `analytics` reading from operational domains

## Scheduling and Background Work

Scheduling is an entrypoint, not business logic.

Rules:

- scheduled classes belong in `job/`
- jobs invoke use cases and contain no business rules
- jobs contain no SQL
- jobs are safe to re-run
- jobs do not own retry, selection, ranking, or state transitions; use cases own those concerns

## Current Migration Direction

The current root packages map to the target architecture as follows:

- `controller/*` -> corresponding module `web/*`
- `service/news/*` -> `news/*`
- `service/brief/*` -> `brief/*`
- `service/profile/*` -> `profile/*`
- `service/delivery/*` -> mostly `telegram/*` and, where needed, `brief/usecase/*`
- `service/analytics/*` -> `analytics/*`
- `service/feedback/*` -> `feedback/*`
- `service/premium/*` -> `premium/*`
- `service/scheduler/*` -> owning module `job/*`
- `service/ai/*` -> `ai/model/*` and `ai/integration/*`
- `repository/*` -> owning module `persistence/*`

High-priority refactoring targets:

- split SQL out of `DailyBriefService`
- split SQL out of `UserProfileService`
- split SQL out of `ProductReportService`
- break `TelegramOnboardingBotService` into Telegram module use cases, formatters, and parsers

## Architecture Tests

ArchUnit must enforce:

- module boundary direction
- no cross-module dependency on another module's `persistence/*`
- no `DatabaseClient` usage outside persistence
- no new code under legacy root `service/`, `controller/`, `repository/`
- one public method rule for `usecase` classes

## Decisions and Changes

Architecture-level changes must:

- update this document
- add or update an ADR in `docs/adr/`
- update ArchUnit rules when boundaries change
