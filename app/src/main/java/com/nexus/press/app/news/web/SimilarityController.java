package com.nexus.press.app.news.web;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsItem;
import com.nexus.press.app.news.usecase.BuildNewsClusters;
import com.nexus.press.app.news.usecase.FindNewsCluster;
import com.nexus.press.app.news.usecase.FindSimilarNews;
import com.nexus.press.app.web.generated.api.SimilarityApiDelegate;
import com.nexus.press.app.web.generated.model.Cluster;
import com.nexus.press.app.web.generated.model.SimilarItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class SimilarityController implements SimilarityApiDelegate {

	private final BuildNewsClusters buildNewsClusters;
	private final FindNewsCluster findNewsCluster;
	private final FindSimilarNews findSimilarNews;
	private final SimilarityProperties similarityProperties;

	@Override
	public Mono<Cluster> getClusterByNewsId(
		final String id,
		final Double threshold,
		final ServerWebExchange exchange
	) {
		final double safeThreshold = threshold != null ? threshold : similarityProperties.getClusterMinScore();
		return findNewsCluster.execute(id, safeThreshold)
			.map(this::toApiCluster);
	}

	@Override
	public Flux<Cluster> getClusters(final Double threshold, final ServerWebExchange exchange) {
		final double safeThreshold = threshold != null ? threshold : similarityProperties.getClusterMinScore();
		return buildNewsClusters.execute(safeThreshold)
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
		return findSimilarNews.execute(id, safeTopN, safeMinScore)
			.map(this::toApiSimilarItem);
	}

	private Cluster toApiCluster(final NewsCluster source) {
		return new Cluster(source.ids(), source.representativeId());
	}

	private SimilarItem toApiSimilarItem(final SimilarNewsItem source) {
		return new SimilarItem(source.id(), source.score());
	}
}
