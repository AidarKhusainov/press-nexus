package com.nexus.press.app.news.persistence.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.nexus.press.app.news.model.NewsEmbeddingVector;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;

public interface NewsSimilarityRepository {

	Flux<SimilarNewsNeighbor> topSimilar(String id, int topN, double minScore);

	Mono<Void> upsertEmbedding(String id, float[] embedding);

	Flux<NewsEmbeddingVector> allEmbeddings();

	Flux<String> allIds();

	Flux<SimilarNewsNeighbor> neighbors(String id, double minScore);
}
