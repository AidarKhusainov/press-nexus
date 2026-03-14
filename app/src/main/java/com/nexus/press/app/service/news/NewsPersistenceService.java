package com.nexus.press.app.service.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPersistenceService {

	private static final String STATUS_PENDING = ProcessingStatus.PENDING.name();
	private static final HexFormat HEX = HexFormat.of();

	private final DatabaseClient db;

	public record PipelineBacklogSnapshot(
		long contentPending,
		long contentInProgress,
		long contentFailed,
		long embeddingPending,
		long embeddingInProgress,
		long embeddingFailed,
		long summaryPending,
		long summaryInProgress,
		long summaryFailed
	) {

		public long totalOutstanding() {
			return contentPending + contentInProgress
				+ embeddingPending + embeddingInProgress
				+ summaryPending + summaryInProgress;
		}
	}

	public record CachedSummary(
		String model,
		String lang,
		String summary
	) {
	}

	public record SummaryPriorityCandidate(
		String newsId,
		String media,
		OffsetDateTime eventAt
	) {
	}

	public Mono<NewsEntity> upsert(final NewsUpsertRequest req) {
		final var contentHash = computeHash(req.getContentClean() != null ? req.getContentClean() : req.getContentRaw());
		final var id = req.getId() != null ? req.getId() : java.util.UUID.randomUUID().toString();
		final var fallbackFetchedAt = req.getFetchedAt() != null ? req.getFetchedAt() : OffsetDateTime.now();

		return upsertByUrl(req, id, contentHash, fallbackFetchedAt, baseInsertSql("ON CONFLICT ON CONSTRAINT news_url_uq DO UPDATE"))
			.onErrorResume(DuplicateKeyException.class, e -> resolveDuplicateUpsert(
				req,
				id,
				contentHash,
				fallbackFetchedAt,
				baseInsertSql("ON CONFLICT (media, external_id) WHERE external_id IS NOT NULL DO UPDATE")
			));
	}

	public Mono<NewsEntity> upsertDiscovered(final NewsUpsertRequest req) {
		final var contentHash = computeHash(req.getContentClean() != null ? req.getContentClean() : req.getContentRaw());
		final var id = req.getId() != null ? req.getId() : java.util.UUID.randomUUID().toString();
		final var fallbackFetchedAt = req.getFetchedAt() != null ? req.getFetchedAt() : OffsetDateTime.now();

		return upsertByUrl(req, id, contentHash, fallbackFetchedAt, discoveryInsertSql("ON CONFLICT ON CONSTRAINT news_url_uq DO UPDATE"))
			.onErrorResume(DuplicateKeyException.class, e -> resolveDuplicateUpsert(
				req,
				id,
				contentHash,
				fallbackFetchedAt,
				discoveryInsertSql("ON CONFLICT (media, external_id) WHERE external_id IS NOT NULL DO UPDATE")
			));
	}

	public Mono<Void> updateStatusContent(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_content", "content_claimed_at", status);
	}

	public Mono<Void> updateStatusEmbedding(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_embedding", "embedding_claimed_at", status);
	}

	public Mono<Void> updateStatusSummary(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_summary", "summary_claimed_at", status);
	}

	public Mono<Void> saveNewsSummary(
		final String newsId,
		final String model,
		final String lang,
		final String summary,
		final String promptHash
	) {
		if (newsId == null || model == null || model.isBlank() || lang == null || lang.isBlank()) {
			return Mono.empty();
		}
		if (summary == null || summary.isBlank()) {
			return Mono.empty();
		}

		final String sql = """
			INSERT INTO news_summary(news_id, model, lang, summary, prompt_hash)
			VALUES (:newsId, :model, :lang, :summary, :promptHash)
			ON CONFLICT (news_id, model, lang) DO UPDATE
			SET summary = EXCLUDED.summary,
			    prompt_hash = EXCLUDED.prompt_hash,
			    created_at = now()
			""";

		var spec = db.sql(sql)
			.bind("newsId", newsId)
			.bind("model", model)
			.bind("lang", lang)
			.bind("summary", summary);
		spec = bindOrNull(spec, "promptHash", promptHash, String.class);
		return spec.fetch().rowsUpdated().then();
	}

	public Flux<RawNews> claimNewsPendingContent(final int limit, final Duration claimTimeout) {
		final int safeLimit = Math.max(1, limit);
		final long leaseSeconds = safeLeaseSeconds(claimTimeout);
			final String sql = """
			WITH claimed AS (
				SELECT n.id
				FROM news n
				WHERE n.status_content = 'PENDING'
				   OR (
				        n.status_content = 'IN_PROGRESS'
				        AND (
				             n.content_claimed_at IS NULL
				             OR n.content_claimed_at < now() - (:leaseSeconds * interval '1 second')
				        )
				   )
				ORDER BY n.created_at ASC, n.id ASC
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
			)
			UPDATE news n
			SET status_content = 'IN_PROGRESS',
			    content_claimed_at = now(),
			    updated_at = now()
			FROM claimed
			WHERE n.id = claimed.id
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean, n.content_hash,
			          n.media, n.published_at, n.fetched_at, n.language
			""";

		return db.sql(sql)
			.bind("leaseSeconds", leaseSeconds)
			.bind("limit", safeLimit)
			.map(this::mapRawNewsRow)
			.all();
	}

	public Flux<RawNews> claimNewsPendingEmbedding(final int limit, final Duration claimTimeout) {
		final int safeLimit = Math.max(1, limit);
		final long leaseSeconds = safeLeaseSeconds(claimTimeout);
			final String sql = """
			WITH claimed AS (
				SELECT n.id
				FROM news n
				WHERE n.status_content = 'DONE'
				  AND n.content_clean IS NOT NULL
				  AND btrim(n.content_clean) <> ''
				  AND (
				       n.status_embedding = 'PENDING'
				       OR (
				            n.status_embedding = 'DONE'
				            AND NOT EXISTS (SELECT 1 FROM news_embedding e WHERE e.news_id = n.id)
				       )
				       OR (
				            n.status_embedding = 'IN_PROGRESS'
				            AND (
				                 n.embedding_claimed_at IS NULL
				                 OR n.embedding_claimed_at < now() - (:leaseSeconds * interval '1 second')
				            )
				       )
				  )
				ORDER BY n.created_at ASC, n.id ASC
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
			)
			UPDATE news n
			SET status_embedding = 'IN_PROGRESS',
			    embedding_claimed_at = now(),
			    updated_at = now()
			FROM claimed
			WHERE n.id = claimed.id
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean, n.content_hash,
			          n.media, n.published_at, n.fetched_at, n.language
			""";

		return db.sql(sql)
			.bind("leaseSeconds", leaseSeconds)
			.bind("limit", safeLimit)
			.map(this::mapRawNewsRow)
			.all();
	}

	public Flux<RawNews> claimNewsPendingSummary(final int limit, final Duration claimTimeout, final Duration maturityWindow) {
		final int safeLimit = Math.max(1, limit);
		final long leaseSeconds = safeLeaseSeconds(claimTimeout);
		final long maturitySeconds = Math.max(0L, maturityWindow == null ? 0L : maturityWindow.toSeconds());
			final String sql = """
			WITH claimed AS (
				SELECT n.id
				FROM news n
				WHERE n.status_content = 'DONE'
				  AND n.status_embedding = 'DONE'
				  AND n.content_clean IS NOT NULL
				  AND btrim(n.content_clean) <> ''
				  AND COALESCE(n.published_at, n.fetched_at, n.created_at)
				      <= now() - (:maturitySeconds * interval '1 second')
				  AND (
				       n.status_summary = 'PENDING'
				       OR (
				            n.status_summary = 'IN_PROGRESS'
				            AND (
				                 n.summary_claimed_at IS NULL
				                 OR n.summary_claimed_at < now() - (:leaseSeconds * interval '1 second')
				            )
				       )
				  )
				ORDER BY n.created_at ASC, n.id ASC
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
			)
			UPDATE news n
			SET status_summary = 'IN_PROGRESS',
			    summary_claimed_at = now(),
			    updated_at = now()
			FROM claimed
			WHERE n.id = claimed.id
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean, n.content_hash,
			          n.media, n.published_at, n.fetched_at, n.language
			""";

		return db.sql(sql)
			.bind("leaseSeconds", leaseSeconds)
			.bind("maturitySeconds", maturitySeconds)
			.bind("limit", safeLimit)
			.map(this::mapRawNewsRow)
			.all();
	}

	public Mono<CachedSummary> findReusableSummary(final String newsId, final String contentHash, final String lang) {
		if (!StringUtils.hasText(lang) || !StringUtils.hasText(newsId) && !StringUtils.hasText(contentHash)) {
			return Mono.empty();
		}

		final String sql = """
			SELECT s.model, s.lang, s.summary
			FROM news_summary s
			JOIN news n ON n.id = s.news_id
			WHERE (s.news_id = :newsId OR n.content_hash = :contentHash)
			  AND s.lang = :lang
			ORDER BY
				CASE WHEN s.news_id = :newsId THEN 3 ELSE 0 END DESC,
				s.created_at DESC
			LIMIT 1
			""";

		var spec = db.sql(sql)
			.bind("lang", lang);
		spec = bindOrNull(spec, "newsId", newsId, String.class);
		spec = bindOrNull(spec, "contentHash", contentHash, String.class);
		return spec.map((row, md) -> new CachedSummary(
				row.get("model", String.class),
				row.get("lang", String.class),
				row.get("summary", String.class)
			))
			.one();
	}

	public Flux<SummaryPriorityCandidate> loadSummaryPriorityCandidates(final OffsetDateTime from) {
		final String sql = """
			SELECT n.id AS news_id,
			       n.media,
			       COALESCE(n.published_at, n.fetched_at, n.created_at) AS event_at
			FROM news n
			WHERE n.status_content = 'DONE'
			  AND n.status_embedding = 'DONE'
			  AND n.content_clean IS NOT NULL
			  AND btrim(n.content_clean) <> ''
			  AND n.status_summary IN ('PENDING', 'IN_PROGRESS')
			  AND COALESCE(n.published_at, n.fetched_at, n.created_at) >= :fromTs
			""";

		return db.sql(sql)
			.bind("fromTs", from)
			.map((row, md) -> new SummaryPriorityCandidate(
				row.get("news_id", String.class),
				row.get("media", String.class),
				row.get("event_at", OffsetDateTime.class)
			))
			.all();
	}

	public Mono<PipelineBacklogSnapshot> loadPipelineBacklog() {
			final String sql = """
			SELECT
				COUNT(*) FILTER (WHERE n.status_content = 'PENDING') AS content_pending,
				COUNT(*) FILTER (WHERE n.status_content = 'IN_PROGRESS') AS content_in_progress,
				COUNT(*) FILTER (WHERE n.status_content = 'FAILED') AS content_failed,
				COUNT(*) FILTER (
					WHERE n.status_content = 'DONE'
					  AND n.content_clean IS NOT NULL
					  AND btrim(n.content_clean) <> ''
					  AND (
						   n.status_embedding = 'PENDING'
						   OR (
						        n.status_embedding = 'DONE'
						        AND NOT EXISTS (SELECT 1 FROM news_embedding e WHERE e.news_id = n.id)
						   )
					  )
				) AS embedding_pending,
				COUNT(*) FILTER (WHERE n.status_embedding = 'IN_PROGRESS') AS embedding_in_progress,
				COUNT(*) FILTER (WHERE n.status_embedding = 'FAILED') AS embedding_failed,
				COUNT(*) FILTER (
					WHERE n.status_content = 'DONE'
					  AND n.status_embedding = 'DONE'
					  AND n.content_clean IS NOT NULL
					  AND btrim(n.content_clean) <> ''
					  AND n.status_summary = 'PENDING'
				) AS summary_pending,
				COUNT(*) FILTER (WHERE n.status_summary = 'IN_PROGRESS') AS summary_in_progress,
				COUNT(*) FILTER (WHERE n.status_summary = 'FAILED') AS summary_failed
			FROM news n
			""";

		return db.sql(sql)
			.map((row, md) -> new PipelineBacklogSnapshot(
				asLong(row.get("content_pending")),
				asLong(row.get("content_in_progress")),
				asLong(row.get("content_failed")),
				asLong(row.get("embedding_pending")),
				asLong(row.get("embedding_in_progress")),
				asLong(row.get("embedding_failed")),
				asLong(row.get("summary_pending")),
				asLong(row.get("summary_in_progress")),
				asLong(row.get("summary_failed"))
			))
			.one()
			.defaultIfEmpty(new PipelineBacklogSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
	}

	private Mono<NewsEntity> upsertByUrl(
		final NewsUpsertRequest req,
		final String id,
		final String hash,
		final OffsetDateTime fetchedAt,
		final String sql
	) {
		return bindAndExecute(req, id, hash, fetchedAt, sql);
	}

	private Mono<NewsEntity> resolveDuplicateUpsert(
		final NewsUpsertRequest req,
		final String id,
		final String hash,
		final OffsetDateTime fetchedAt,
		final String retrySql
	) {
		if (StringUtils.hasText(req.getExternalId())) {
			log.warn("URL upsert conflict, retry by (media, external_id): url={} media={} extId={}",
				req.getUrl(), req.getMedia(), req.getExternalId());
			return bindAndExecute(req, id, hash, fetchedAt, retrySql)
				.onErrorResume(DuplicateKeyException.class, ex -> findExistingByNaturalKeys(id, req.getUrl(), hash));
		}

		log.warn("URL upsert conflict without external_id, resolving existing row by id/url/hash: id={} url={}", id, req.getUrl());
		return findExistingByNaturalKeys(id, req.getUrl(), hash);
	}

	private String baseInsertSql(final String conflictClause) {
		return "INSERT INTO news (id, media, external_id, url, title, author, language, published_at, fetched_at, " +
			"content_raw, content_clean, content_hash, status_content, status_embedding, status_summary) " +
			"VALUES (:id, :media, :externalId, :url, :title, :author, :language, :publishedAt, :fetchedAt, " +
			":contentRaw, :contentClean, :contentHash, :statusContent, :statusEmbedding, :statusSummary) " +
			conflictClause + " SET " +
			"title = EXCLUDED.title, " +
			"author = COALESCE(EXCLUDED.author, news.author), " +
			"language = COALESCE(EXCLUDED.language, news.language), " +
			"published_at = COALESCE(EXCLUDED.published_at, news.published_at), " +
			"fetched_at = EXCLUDED.fetched_at, " +
			"content_raw = COALESCE(EXCLUDED.content_raw, news.content_raw), " +
			"content_clean = COALESCE(EXCLUDED.content_clean, news.content_clean), " +
			"content_hash = EXCLUDED.content_hash, " +
			"status_content = COALESCE(EXCLUDED.status_content, news.status_content), " +
			"status_embedding = COALESCE(EXCLUDED.status_embedding, news.status_embedding), " +
			"status_summary = COALESCE(EXCLUDED.status_summary, news.status_summary), " +
			"updated_at = now() " +
			"RETURNING *";
	}

	private String discoveryInsertSql(final String conflictClause) {
		return "INSERT INTO news (id, media, external_id, url, title, author, language, published_at, fetched_at, " +
			"content_raw, content_clean, content_hash, status_content, status_embedding, status_summary) " +
			"VALUES (:id, :media, :externalId, :url, :title, :author, :language, :publishedAt, :fetchedAt, " +
			":contentRaw, :contentClean, :contentHash, :statusContent, :statusEmbedding, :statusSummary) " +
			conflictClause + " SET " +
			"title = EXCLUDED.title, " +
			"author = COALESCE(EXCLUDED.author, news.author), " +
			"language = COALESCE(EXCLUDED.language, news.language), " +
			"published_at = COALESCE(EXCLUDED.published_at, news.published_at), " +
			"fetched_at = EXCLUDED.fetched_at, " +
			"updated_at = now() " +
			"RETURNING *";
	}

	private Mono<NewsEntity> bindAndExecute(final NewsUpsertRequest req, final String id, final String hash, final OffsetDateTime fetchedAt, final String sql) {
		final var statusContent = req.getStatusContent() != null ? req.getStatusContent().name() : STATUS_PENDING;
		final var statusEmbedding = req.getStatusEmbedding() != null ? req.getStatusEmbedding().name() : STATUS_PENDING;
		final var statusSummary = req.getStatusSummary() != null ? req.getStatusSummary().name() : STATUS_PENDING;

		var spec = db.sql(sql)
			.bind("id", id)
			.bind("media", req.getMedia())
			.bind("url", req.getUrl())
			.bind("title", req.getTitle())
			.bind("fetchedAt", fetchedAt)
			.bind("contentHash", hash)
			.bind("statusContent", statusContent)
			.bind("statusEmbedding", statusEmbedding)
			.bind("statusSummary", statusSummary);

		spec = bindOrNull(spec, "externalId", req.getExternalId(), String.class);
		spec = bindOrNull(spec, "author", req.getAuthor(), String.class);
		spec = bindOrNull(spec, "language", req.getLanguage(), String.class);
		spec = bindOrNull(spec, "publishedAt", req.getPublishedAt(), OffsetDateTime.class);
		spec = bindOrNull(spec, "contentRaw", req.getContentRaw(), String.class);
		spec = bindOrNull(spec, "contentClean", req.getContentClean(), String.class);

		return spec.map((row, md) -> mapNewsEntity(row))
			.one();
	}

	private Mono<NewsEntity> findExistingByNaturalKeys(final String id, final String url, final String contentHash) {
		final String sql = """
			SELECT *
			FROM news
			WHERE id = :id
			   OR url = :url
			   OR content_hash = :contentHash
			ORDER BY
				CASE WHEN id = :id THEN 3 ELSE 0 END +
				CASE WHEN url = :url THEN 2 ELSE 0 END +
				CASE WHEN content_hash = :contentHash THEN 1 ELSE 0 END DESC
			LIMIT 1
			""";

		return db.sql(sql)
			.bind("id", id)
			.bind("url", url)
			.bind("contentHash", contentHash)
			.map((row, md) -> mapNewsEntity(row))
			.one()
			.switchIfEmpty(Mono.error(new IllegalStateException(
				"Не удалось разрешить конфликт upsert: существующая запись не найдена")));
	}

	private NewsEntity mapNewsEntity(final Row row) {
		return NewsEntity.builder()
			.id(row.get("id", String.class))
			.media(row.get("media", String.class))
			.externalId(row.get("external_id", String.class))
			.url(row.get("url", String.class))
			.title(row.get("title", String.class))
			.author(row.get("author", String.class))
			.language(row.get("language", String.class))
			.publishedAt(row.get("published_at", OffsetDateTime.class))
			.fetchedAt(row.get("fetched_at", OffsetDateTime.class))
			.contentRaw(row.get("content_raw", String.class))
			.contentClean(row.get("content_clean", String.class))
			.contentHash(row.get("content_hash", String.class))
			.statusContent(row.get("status_content", String.class))
			.statusEmbedding(row.get("status_embedding", String.class))
			.statusSummary(row.get("status_summary", String.class))
			.createdAt(row.get("created_at", OffsetDateTime.class))
			.updatedAt(row.get("updated_at", OffsetDateTime.class))
			.build();
	}

	private <T> org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bindOrNull(
		final org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec,
		final String name,
		final T value,
		final Class<T> type
	) {
		return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
	}

	private String computeHash(final String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Для сохранения новости требуется непустой контент");
		}
		try {
			final MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HEX.formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	private long safeLeaseSeconds(final Duration claimTimeout) {
		if (claimTimeout == null || claimTimeout.isNegative() || claimTimeout.isZero()) {
			return Duration.ofMinutes(30).toSeconds();
		}
		return claimTimeout.toSeconds();
	}

	private long asLong(final Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		return 0L;
	}

	private Mono<Void> updateStatus(final String id, final String column, final String claimedAtColumn, final ProcessingStatus status) {
		if (id == null) return Mono.empty();
		return db.sql(
			"UPDATE news SET " + column + " = :status, " + claimedAtColumn + " = CASE " +
				"WHEN :status = 'IN_PROGRESS' THEN now() ELSE NULL END, updated_at = now() WHERE id = :id"
		)
			.bind("status", status.name())
			.bind("id", id)
			.fetch().rowsUpdated().then();
	}

	private Media toMedia(final String mediaValue) {
		if (mediaValue == null || mediaValue.isBlank()) return null;
		try {
			return Media.valueOf(mediaValue);
		} catch (final IllegalArgumentException ex) {
			log.warn("Неизвестный media='{}' при восстановлении эмбеддинга", mediaValue);
			return null;
		}
	}

	private RawNews mapRawNewsRow(final Row row, final RowMetadata md) {
		final var mediaValue = row.get("media", String.class);
		return RawNews.builder()
			.id(row.get("id", String.class))
			.link(row.get("url", String.class))
			.title(row.get("title", String.class))
			.description(row.get("title", String.class))
			.rawContent(row.get("content_raw", String.class))
			.cleanContent(row.get("content_clean", String.class))
			.source(toMedia(mediaValue))
			.publishedDate(row.get("published_at", OffsetDateTime.class))
			.fetchedDate(row.get("fetched_at", OffsetDateTime.class))
			.contentHash(row.get("content_hash", String.class))
			.language(row.get("language", String.class))
			.build();
	}
}
