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
public class FindNewsCluster {

	private final NewsSimilarityRepository newsSimilarityRepository;

	public Mono<NewsCluster> execute(final String id, final double threshold) {
		return newsSimilarityRepository.allIds()
			.collectList()
			.flatMap(ids -> buildAdjacency(ids, threshold)
				.map(adjacency -> NewsClusterGraph.clusterOf(id, adjacency)));
	}

	private Mono<Map<String, List<SimilarNewsNeighbor>>> buildAdjacency(
		final List<String> ids,
		final double threshold
	) {
		return Flux.fromIterable(ids)
			.flatMap(currentId -> newsSimilarityRepository.neighbors(currentId, threshold)
				.collectList()
				.map(neighbors -> Map.entry(currentId, neighbors)))
			.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}
}
