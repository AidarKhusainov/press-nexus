package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.util.List;
import com.nexus.press.app.service.news.model.ProcessedNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSimilarityService {

	private final ReactiveNewsSimilarityStore store;

	/**
	 * Реактивно вычисляет и сохраняет схожесть, апсертит эмбеддинг и возвращает топ-N похожих.
	 */
    public Mono<List<NewsSimilarityStore.SimilarItem>> observe(final ProcessedNews news, final int topN, final double minScore) {
        final float[] emb = news.getContentEmbedding();
        if (emb == null || emb.length == 0) {
            log.debug("Нет эмбеддинга у новости {}, пропускаем similarity", news.getId());
            return Mono.just(List.of());
        }

        final String id = news.getId();
        // Апсертим эмбеддинг и выполняем KNN в pgvector. news_similarity больше не используется.
        return store.upsertEmbedding(id, emb)
            .thenMany(store.topSimilar(id, topN, minScore))
            .collectList()
            .doOnNext(top -> { if (!top.isEmpty()) log.info("Top similar for {}: {}", id, top); });
    }
}
