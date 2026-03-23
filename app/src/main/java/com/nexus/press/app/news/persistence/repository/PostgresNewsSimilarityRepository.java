package com.nexus.press.app.news.persistence.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import com.nexus.press.app.news.model.NewsEmbeddingVector;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Component
public class PostgresNewsSimilarityRepository implements NewsSimilarityRepository {

	private final DatabaseClient db;

	public PostgresNewsSimilarityRepository(final DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Flux<SimilarNewsNeighbor> topSimilar(final String id, final int topN, final double minScore) {
		final String sql =
			"SELECT n.news_id AS neighbor, 1 - (n.embedding <=> q.embedding) AS score " +
			"FROM news_embedding n " +
			"JOIN (SELECT embedding FROM news_embedding WHERE news_id = :id) q ON true " +
			"WHERE n.news_id <> :id " +
			"  AND 1 - (n.embedding <=> q.embedding) >= :min " +
			"ORDER BY n.embedding <=> q.embedding " +
			"LIMIT :lim";
		return db.sql(sql)
			.bind("id", id)
			.bind("min", minScore)
			.bind("lim", topN)
			.map(PostgresNewsSimilarityRepository::mapSimilar)
			.all();
	}

	@Override
	public Mono<Void> upsertEmbedding(final String id, final float[] embedding) {
		final String vectorLiteral = toPgVectorLiteral(embedding);
		return db.sql("INSERT INTO news_embedding(news_id, embedding) VALUES (:id, CAST(:emb AS vector)) " +
				"ON CONFLICT (news_id) DO UPDATE SET embedding = CAST(EXCLUDED.embedding AS vector)")
			.bind("id", id)
			.bind("emb", vectorLiteral)
			.fetch()
			.rowsUpdated()
			.then();
	}

	@Override
	public Flux<NewsEmbeddingVector> allEmbeddings() {
		return db.sql("SELECT news_id, embedding::float4[] FROM news_embedding")
			.map(PostgresNewsSimilarityRepository::mapEmbedding)
			.all();
	}

	@Override
	public Flux<String> allIds() {
		return db.sql("SELECT news_id FROM news_embedding")
			.map((row, metadata) -> row.get(0, String.class))
			.all();
	}

	@Override
	public Flux<SimilarNewsNeighbor> neighbors(final String id, final double minScore) {
		final int neighborK = 100;
		final String sql =
			"SELECT n.news_id AS neighbor, 1 - (n.embedding <=> q.embedding) AS score " +
			"FROM news_embedding n " +
			"JOIN (SELECT embedding FROM news_embedding WHERE news_id = :id) q ON true " +
			"WHERE n.news_id <> :id " +
			"  AND 1 - (n.embedding <=> q.embedding) >= :min " +
			"ORDER BY n.embedding <=> q.embedding " +
			"LIMIT :lim";
		return db.sql(sql)
			.bind("id", id)
			.bind("min", minScore)
			.bind("lim", neighborK)
			.map(PostgresNewsSimilarityRepository::mapSimilar)
			.all();
	}

	private static SimilarNewsNeighbor mapSimilar(final Row row, final RowMetadata metadata) {
		final String neighbor = row.get(0, String.class);
		final Double score = row.get(1, Double.class);
		return new SimilarNewsNeighbor(neighbor, score != null ? score : 0d);
	}

	private static NewsEmbeddingVector mapEmbedding(final Row row, final RowMetadata metadata) {
		final String id = row.get(0, String.class);
		final Object[] doubles = (Object[]) row.get(1);
		final float[] embedding;
		if (doubles == null) {
			embedding = new float[0];
		} else {
			embedding = new float[doubles.length];
			for (int i = 0; i < doubles.length; i++) {
				final Object value = doubles[i];
				embedding[i] = value == null ? 0f : ((Number) value).floatValue();
			}
		}
		return new NewsEmbeddingVector(id, embedding);
	}

	private static String toPgVectorLiteral(final float[] embedding) {
		if (embedding == null || embedding.length == 0) {
			return "[]";
		}

		final StringBuilder sb = new StringBuilder(embedding.length * 8);
		sb.append('[');
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(Float.toString(embedding[i]));
		}
		sb.append(']');
		return sb.toString();
	}
}
