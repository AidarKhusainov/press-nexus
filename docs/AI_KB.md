# Knowledge Base: Press-Nexus (for AI)

## Purpose and Vision
- Goal: aggregate open sources ("white internet") — media, social networks, forums, blogs — normalize content, build embeddings, generate concise summaries, and automatically route materials into "smart folders" (topics) for reports and dossiers.
- Current state: module `app` (Spring Boot, WebFlux) implements the chain for news ingestion, enrichment, embeddings, and summarization powered by Ollama.

## Architecture (Current)
- Entry point: `app/src/main/java/com/nexus/press/app/AppApplication.java:1`.
- Config/HTTP clients/AI: `app/src/main/java/com/nexus/press/app/config/**`.
- News (ETL stages): `app/src/main/java/com/nexus/press/app/service/news/**`.
- Queues and consumers: `app/src/main/java/com/nexus/press/app/service/queue/**`.
- Scheduler: `app/src/main/java/com/nexus/press/app/service/scheduler/**`.
- Utilities (chunking/vectors): `app/src/main/java/com/nexus/press/app/util/**`.
- Settings: `app/src/main/resources/application.properties:1`.
- External services: RIA, NYTimes RSS, Jina Reader; ML backend — Ollama (`docker-compose.yml:1`).

## Data Flow (ETL)
1) Fetch: load feeds/pages from sources.
2) Populate: extract main text (Jina Reader), normalize/clean.
3) Embedding: vectorization (Ollama `nomic-embed-text`).
4) Summarization: short summaries for preview/search.
5) (Planned) Classification: assign "smart folders" using embeddings/rules.
6) (Planned) Storage & Search: index into storage (ES/OpenSearch/PG+pgvector).

## Configuration and Environment
- AI transport/config: `http-client.clients.OLLAMA.*` and `http-client.clients.GEMINI.*` in `application.properties:1`; models selected in services.
- HTTP clients: `http-client.clients.*` (base-url, timeouts, retries) — tuned per source.
- Commands: build `./mvnw clean verify`, run `./mvnw -pl app spring-boot:run`, tests `./mvnw test`.

## "Smart Folders" (Design)
- Taxonomy: list of topics with rules and/or vector centroids.
- Classification: hybrid approach —
  - Rules: keywords/regex/domains (`source`, `url`, `title`).
  - Semantic: cosine similarity to folder centroids; thresholds/boosts.
- Topic updates: recalculate centroids from accumulated materials; audit/manual correction.
- Output: `topics=[{id,score}]` field in document; mapped to folders for reporting.

## Data Model (Proposal)
- Item: `{id, url, source, fetchedAt, language, title, text, chunks[], embedding, summary, topics[], meta{}}`.
- TopicFolder: `{id, name, rules[], centroidEmbedding, threshold}`.
- Enrichment: `language`, `entities`, `authors`, `geo` (later stages).

## Source Expansion (Robots)
- Connectors by type: `rss`, `html+readability`, `api`, `forum`, `social`.
- Robot parameters: `rateLimit`, `schedule`, `parser`, `auth?`, `startUrls`, `dedupKeys`.
- Quality stages: deduplication (URL normalization + content hashes), anti-spam, language detection.
- Ethics: respect `robots.txt`, use backoff, do not bypass paid/restricted zones.

## Queues and Scaling
- Current: in-process queues/consumers in `service/queue/**`.
- Plan: move to broker (RabbitMQ/Redis/Kafka) with idempotency keys, retries, and DLQ.
- Horizontal scale: separate workers per stage (fetch/populate/embed/summarize/classify).

## Search and Storage (Plan)
- Options: Elasticsearch/OpenSearch (full-text) + vector search, or PostgreSQL + pgvector.
- Index fields: `title`, `text`, `summary`, `source`, `time`, `topics`, `embedding`.
- Features: similar articles, clustering, trend detection by topic.

## Metrics and Quality
- Source coverage, freshness (time-to-index), text completeness, duplicate ratio.
- Classification quality: precision/recall against labeled samples; auto-assignment thresholds.
- Cost: tokens/time for embedding/summary; caching and batching.

## Security and Legal
- Do not store secrets in repository; use environment variables for keys.
- Follow licenses/site terms; do not collect unnecessary personal data.
- Logs: handle PII carefully; anonymize/redact when needed.

## Roadmap (priority)
1) Introduce `Connector/Robot` abstraction + configurable sources.
2) Build "smart folders" classifier (rules + embeddings, centroids/thresholds).
3) Add search storage (ES/OpenSearch or PG+pgvector) and retrieval API.
4) Improve dedup/normalization: URL canonicalization, content hashes, near-dup.
5) Model ops: embedding cache/batching; model tuning for ru/en domains.
6) Observability: metrics, alerts, tracing.

## Quick Links
- Entry point: `app/src/main/java/com/nexus/press/app/AppApplication.java:1`
- Properties: `app/src/main/resources/application.properties:1`
- POM (root): `pom.xml:1`
- Docker Compose (Ollama): `docker-compose.yml:1`
