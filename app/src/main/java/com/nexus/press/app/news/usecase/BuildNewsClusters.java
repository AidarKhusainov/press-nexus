package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import com.nexus.press.app.news.policy.NewsClusterGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BuildNewsClusters {

	private final NewsSimilarityRepository newsSimilarityRepository;

	public Mono<List<NewsCluster>> execute(final double threshold) {
		return newsSimilarityRepository.allIds()
			.collectList()
			.flatMap(ids -> buildAdjacency(ids, threshold)
				.map(adjacency -> NewsClusterGraph.computeClusters(ids, adjacency)));
	}

	private Mono<Map<String, List<SimilarNewsNeighbor>>> buildAdjacency(
		final List<String> ids,
		final double threshold
	) {
		return Flux.fromIterable(ids)
			.flatMap(id -> newsSimilarityRepository.neighbors(id, threshold)
				.collectList()
				.map(neighbors -> Map.entry(id, neighbors)))
			.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}
}
