package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReactiveNewsSimilarityStore {

	Mono<Void> put(String idA, String idB, double similarity);

	Flux<NewsSimilarityStore.SimilarItem> topSimilar(String id, int topN, double minScore);

	Mono<Void> upsertEmbedding(String id, float[] embedding);

	Flux<NewsSimilarityStore.EmbeddingItem> allEmbeddings();

	Flux<String> allIds();

	Flux<NewsSimilarityStore.SimilarItem> neighbors(String id, double minScore);
}

