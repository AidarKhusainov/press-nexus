package com.nexus.press.app.news.persistence.query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import com.nexus.press.app.news.model.NewsPipelineBacklogSnapshot;
import com.nexus.press.app.news.model.NewsSummaryPriorityCandidate;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.RawNews;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PostgresNewsPipelineQuery {

	private final DatabaseClient db;

	public PostgresNewsPipelineQuery(final DatabaseClient db) {
		this.db = db;
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
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean,
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
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean,
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
			RETURNING n.id, n.url, n.title, n.content_raw, n.content_clean,
			          n.media, n.published_at, n.fetched_at, n.language
			""";

		return db.sql(sql)
			.bind("leaseSeconds", leaseSeconds)
			.bind("maturitySeconds", maturitySeconds)
			.bind("limit", safeLimit)
			.map(this::mapRawNewsRow)
			.all();
	}

	public Flux<NewsSummaryPriorityCandidate> loadSummaryPriorityCandidates(final OffsetDateTime from) {
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
			.map((row, metadata) -> new NewsSummaryPriorityCandidate(
				row.get("news_id", String.class),
				row.get("media", String.class),
				row.get("event_at", OffsetDateTime.class)
			))
			.all();
	}

	public Mono<NewsPipelineBacklogSnapshot> loadPipelineBacklog() {
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
			.map((row, metadata) -> new NewsPipelineBacklogSnapshot(
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
			.defaultIfEmpty(new NewsPipelineBacklogSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
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

	private Media toMedia(final String mediaValue) {
		if (mediaValue == null || mediaValue.isBlank()) {
			return null;
		}
		try {
			return Media.valueOf(mediaValue);
		} catch (final IllegalArgumentException ex) {
			log.warn("Неизвестный media='{}' при восстановлении эмбеддинга", mediaValue);
			return null;
		}
	}

	private RawNews mapRawNewsRow(final Row row, final RowMetadata metadata) {
		final String mediaValue = row.get("media", String.class);
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
			.language(row.get("language", String.class))
			.build();
	}
}
