package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Primary
@Component
public class PostgresReactiveNewsSimilarityStore implements ReactiveNewsSimilarityStore {

    private final DatabaseClient db;

    public PostgresReactiveNewsSimilarityStore(final DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> put(final String idA, final String idB, final double similarity) {
        // Начиная с версии без таблицы news_similarity, запись парной схожести не требуется.
        // Оставляем no-op для совместимости интерфейса.
        return Mono.empty();
    }

    @Override
    public Flux<NewsSimilarityStore.SimilarItem> topSimilar(final String id, final int topN, final double minScore) {
        // KNN по индексу pgvector: упорядочиваем по косинусной дистанции и фильтруем по порогу схожести
        final String sql =
            "SELECT n.news_id AS neighbor, 1 - (n.embedding <#> q.embedding) AS score " +
            "FROM news_embedding n " +
            "JOIN (SELECT embedding FROM news_embedding WHERE news_id = :id) q ON true " +
            "WHERE n.news_id <> :id " +
            "  AND 1 - (n.embedding <#> q.embedding) >= :min " +
            "ORDER BY n.embedding <#> q.embedding " +
            "LIMIT :lim";
        return db.sql(sql)
            .bind("id", id)
            .bind("min", minScore)
            .bind("lim", topN)
            .map(PostgresReactiveNewsSimilarityStore::mapSimilar)
            .all();
    }

    @Override
    public Mono<Void> upsertEmbedding(final String id, final float[] embedding) {
        // Build pgvector literal like "[0.1,0.2,...]" and cast to vector
        final String vectorLiteral = toPgVectorLiteral(embedding);
        return db.sql("INSERT INTO news_embedding(news_id, embedding) VALUES (:id, CAST(:emb AS vector)) " +
                "ON CONFLICT (news_id) DO UPDATE SET embedding = CAST(EXCLUDED.embedding AS vector)")
            .bind("id", id)
            .bind("emb", vectorLiteral)
            .fetch().rowsUpdated().then();
    }

    @Override
    public Flux<NewsSimilarityStore.EmbeddingItem> allEmbeddings() {
        // Cast vector back to float4[] for convenient decoding via R2DBC
        return db.sql("SELECT news_id, embedding::float4[] FROM news_embedding")
            .map(PostgresReactiveNewsSimilarityStore::mapEmbedding).all();
    }

    @Override
    public Flux<String> allIds() {
        return db.sql("SELECT news_id FROM news_embedding").map((row, md) -> row.get(0, String.class)).all();
    }

    @Override
    public Flux<NewsSimilarityStore.SimilarItem> neighbors(final String id, final double minScore) {
        // Для построения графа связности достаточно ограниченного числа ближайших соседей.
        final int neighborK = 100; // разумное K по умолчанию для кластеризации
        final String sql =
            "SELECT n.news_id AS neighbor, 1 - (n.embedding <#> q.embedding) AS score " +
            "FROM news_embedding n " +
            "JOIN (SELECT embedding FROM news_embedding WHERE news_id = :id) q ON true " +
            "WHERE n.news_id <> :id " +
            "  AND 1 - (n.embedding <#> q.embedding) >= :min " +
            "ORDER BY n.embedding <#> q.embedding " +
            "LIMIT :lim";
        return db.sql(sql)
            .bind("id", id)
            .bind("min", minScore)
            .bind("lim", neighborK)
            .map(PostgresReactiveNewsSimilarityStore::mapSimilar)
            .all();
    }

    private static NewsSimilarityStore.SimilarItem mapSimilar(final Row row, final RowMetadata md) {
        final String neighbor = row.get(0, String.class);
        final Double score = row.get(1, Double.class);
        return new NewsSimilarityStore.SimilarItem(neighbor, score != null ? score : 0d);
    }

    private static NewsSimilarityStore.EmbeddingItem mapEmbedding(final Row row, final RowMetadata md) {
        final String id = row.get(0, String.class);
        final Object[] doubles = (Object[]) row.get(1);
        final float[] f;
        if (doubles == null) {
            f = new float[0];
        } else {
            f = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                final Object o = doubles[i];
                f[i] = o == null ? 0f : ((Number) o).floatValue();
            }
        }
        return new NewsSimilarityStore.EmbeddingItem(id, f);
    }

    private static String toPgVectorLiteral(final float[] embedding) {
        if (embedding == null || embedding.length == 0) return "[]";
        final StringBuilder sb = new StringBuilder(embedding.length * 8);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            // Use decimal string to avoid scientific notation issues
            sb.append(Float.toString(embedding[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
