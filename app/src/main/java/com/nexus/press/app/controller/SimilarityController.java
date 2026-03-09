package com.nexus.press.app.controller;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.service.news.NewsClusteringService;
import com.nexus.press.app.service.news.NewsSimilarityStore;
import com.nexus.press.app.service.news.ReactiveNewsSimilarityStore;
import com.nexus.press.app.web.generated.api.SimilarityApiDelegate;
import com.nexus.press.app.web.generated.model.Cluster;
import com.nexus.press.app.web.generated.model.SimilarItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class SimilarityController implements SimilarityApiDelegate {

	private final NewsClusteringService clusteringService;
	private final SimilarityProperties similarityProperties;
	private final ReactiveNewsSimilarityStore store;

	@Override
	public Mono<Cluster> getClusterByNewsId(
		final String id,
		final Double threshold,
		final ServerWebExchange exchange
	) {
		final double safeThreshold = threshold != null ? threshold : similarityProperties.getClusterMinScore();
		return clusteringService.clusterOf(id, safeThreshold)
			.map(this::toApiCluster);
	}

	@Override
	public Flux<Cluster> getClusters(final Double threshold, final ServerWebExchange exchange) {
		final double safeThreshold = threshold != null ? threshold : similarityProperties.getClusterMinScore();
		return clusteringService.buildClusters(safeThreshold)
			.flatMapMany(Flux::fromIterable)
			.map(this::toApiCluster);
	}

	@Override
	public Flux<SimilarItem> getSimilarNews(
		final String id,
		final Integer topN,
		final Double minScore,
		final ServerWebExchange exchange
	) {
		final int safeTopN = topN != null ? topN : similarityProperties.getTopN();
		final double safeMinScore = minScore != null ? minScore : similarityProperties.getMinScore();
		return store.topSimilar(id, safeTopN, safeMinScore)
			.map(this::toApiSimilarItem);
	}

	private Cluster toApiCluster(final NewsClusteringService.Cluster source) {
		return new Cluster(source.ids(), source.representativeId());
	}

	private SimilarItem toApiSimilarItem(final NewsSimilarityStore.SimilarItem source) {
		return new SimilarItem(source.id(), source.score());
	}
}
