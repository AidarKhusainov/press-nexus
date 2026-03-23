package com.nexus.press.app.news.persistence.repository;

import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import com.nexus.press.app.news.model.CachedNewsSummary;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.ProcessingStatus;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PostgresNewsRepository implements NewsRepository {

	private static final String STATUS_PENDING = ProcessingStatus.PENDING.name();

	private final DatabaseClient db;

	public PostgresNewsRepository(final DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<NewsEntity> upsertByUrl(final NewsUpsertRequest request, final String id, final OffsetDateTime fetchedAt) {
		return bindAndExecute(request, id, fetchedAt, baseInsertSql("ON CONFLICT ON CONSTRAINT news_url_uq DO UPDATE"));
	}

	@Override
	public Mono<NewsEntity> upsertByExternalId(
		final NewsUpsertRequest request,
		final String id,
		final OffsetDateTime fetchedAt
	) {
		return bindAndExecute(
			request,
			id,
			fetchedAt,
			baseInsertSql("ON CONFLICT (media, external_id) WHERE external_id IS NOT NULL DO UPDATE")
		);
	}

	@Override
	public Mono<NewsEntity> saveDiscoveredIfAbsent(
		final NewsUpsertRequest request,
		final String id,
		final OffsetDateTime fetchedAt
	) {
		return bindAndExecute(request, id, fetchedAt, discoveryInsertIfAbsentSql());
	}

	@Override
	public Mono<Void> updateStatusContent(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_content", "content_claimed_at", status);
	}

	@Override
	public Mono<Void> updateStatusEmbedding(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_embedding", "embedding_claimed_at", status);
	}

	@Override
	public Mono<Void> updateStatusSummary(final String id, final ProcessingStatus status) {
		return updateStatus(id, "status_summary", "summary_claimed_at", status);
	}

	@Override
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

	@Override
	public Mono<CachedNewsSummary> findReusableSummary(final String newsId, final String lang) {
		if (!StringUtils.hasText(lang) || !StringUtils.hasText(newsId)) {
			return Mono.empty();
		}

		final String sql = """
			SELECT s.model, s.lang, s.summary
			FROM news_summary s
			WHERE s.news_id = :newsId
			  AND s.lang = :lang
			ORDER BY
				s.created_at DESC
			LIMIT 1
			""";

		var spec = db.sql(sql)
			.bind("lang", lang);
		spec = bindOrNull(spec, "newsId", newsId, String.class);
		return spec.map((row, metadata) -> new CachedNewsSummary(
				row.get("model", String.class),
				row.get("lang", String.class),
				row.get("summary", String.class)
			))
			.one();
	}

	@Override
	public Mono<NewsEntity> loadExistingByNaturalKeys(final String id, final String url) {
		final String sql = """
			SELECT *
			FROM news
			WHERE id = :id
			   OR url = :url
			ORDER BY
				CASE WHEN id = :id THEN 3 ELSE 0 END +
				CASE WHEN url = :url THEN 2 ELSE 0 END DESC
			LIMIT 1
			""";

		return db.sql(sql)
			.bind("id", id)
			.bind("url", url)
			.map((row, metadata) -> mapNewsEntity(row))
			.one();
	}

	private Mono<NewsEntity> bindAndExecute(
		final NewsUpsertRequest request,
		final String id,
		final OffsetDateTime fetchedAt,
		final String sql
	) {
		final String statusContent = request.getStatusContent() != null ? request.getStatusContent().name() : STATUS_PENDING;
		final String statusEmbedding = request.getStatusEmbedding() != null ? request.getStatusEmbedding().name() : STATUS_PENDING;
		final String statusSummary = request.getStatusSummary() != null ? request.getStatusSummary().name() : STATUS_PENDING;

		var spec = db.sql(sql)
			.bind("id", id)
			.bind("media", request.getMedia())
			.bind("url", request.getUrl())
			.bind("title", request.getTitle())
			.bind("fetchedAt", fetchedAt)
			.bind("statusContent", statusContent)
			.bind("statusEmbedding", statusEmbedding)
			.bind("statusSummary", statusSummary);

		spec = bindOrNull(spec, "externalId", request.getExternalId(), String.class);
		spec = bindOrNull(spec, "author", request.getAuthor(), String.class);
		spec = bindOrNull(spec, "language", request.getLanguage(), String.class);
		spec = bindOrNull(spec, "publishedAt", request.getPublishedAt(), OffsetDateTime.class);
		spec = bindOrNull(spec, "contentRaw", request.getContentRaw(), String.class);
		spec = bindOrNull(spec, "contentClean", request.getContentClean(), String.class);

		return spec.map((row, metadata) -> mapNewsEntity(row))
			.one();
	}

	private Mono<Void> updateStatus(
		final String id,
		final String column,
		final String claimedAtColumn,
		final ProcessingStatus status
	) {
		if (id == null) {
			return Mono.empty();
		}
		return db.sql(
			"UPDATE news SET " + column + " = :status, " + claimedAtColumn + " = CASE " +
				"WHEN :status = 'IN_PROGRESS' THEN now() ELSE NULL END, updated_at = now() WHERE id = :id"
		)
			.bind("status", status.name())
			.bind("id", id)
			.fetch()
			.rowsUpdated()
			.then();
	}

	private String baseInsertSql(final String conflictClause) {
		return "INSERT INTO news (id, media, external_id, url, title, author, language, published_at, fetched_at, " +
			"content_raw, content_clean, status_content, status_embedding, status_summary) " +
			"VALUES (:id, :media, :externalId, :url, :title, :author, :language, :publishedAt, :fetchedAt, " +
			":contentRaw, :contentClean, :statusContent, :statusEmbedding, :statusSummary) " +
			conflictClause + " SET " +
			"external_id = COALESCE(EXCLUDED.external_id, news.external_id), " +
			"url = EXCLUDED.url, " +
			"title = EXCLUDED.title, " +
			"author = COALESCE(EXCLUDED.author, news.author), " +
			"language = COALESCE(EXCLUDED.language, news.language), " +
			"published_at = COALESCE(EXCLUDED.published_at, news.published_at), " +
			"fetched_at = EXCLUDED.fetched_at, " +
			"content_raw = COALESCE(EXCLUDED.content_raw, news.content_raw), " +
			"content_clean = COALESCE(EXCLUDED.content_clean, news.content_clean), " +
			"status_content = COALESCE(EXCLUDED.status_content, news.status_content), " +
			"status_embedding = COALESCE(EXCLUDED.status_embedding, news.status_embedding), " +
			"status_summary = COALESCE(EXCLUDED.status_summary, news.status_summary), " +
			"updated_at = now() " +
			"RETURNING *";
	}

	private String discoveryInsertIfAbsentSql() {
		return "INSERT INTO news (id, media, external_id, url, title, author, language, published_at, fetched_at, " +
			"content_raw, content_clean, status_content, status_embedding, status_summary) " +
			"VALUES (:id, :media, :externalId, :url, :title, :author, :language, :publishedAt, :fetchedAt, " +
			":contentRaw, :contentClean, :statusContent, :statusEmbedding, :statusSummary) " +
			"ON CONFLICT DO NOTHING " +
			"RETURNING *";
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
			.statusContent(row.get("status_content", String.class))
			.statusEmbedding(row.get("status_embedding", String.class))
			.statusSummary(row.get("status_summary", String.class))
			.createdAt(row.get("created_at", OffsetDateTime.class))
			.updatedAt(row.get("updated_at", OffsetDateTime.class))
			.build();
	}

	private <T> DatabaseClient.GenericExecuteSpec bindOrNull(
		final DatabaseClient.GenericExecuteSpec spec,
		final String name,
		final T value,
		final Class<T> type
	) {
		return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
	}
}
