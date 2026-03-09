package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NewsClusteringService {

	private final ReactiveNewsSimilarityStore store;

	/** Кластер новостей и его представитель (идентификатор). */
	public record Cluster(Set<String> ids, String representativeId) {}

	public Mono<List<Cluster>> buildClusters(final double threshold) {
		return store.allIds().collectList()
			.flatMap(ids -> buildAdjacency(ids, threshold)
				.map(adj -> computeClusters(ids, adj))
			);
	}

	public Mono<Cluster> clusterOf(final String id, final double threshold) {
		return store.allIds().collectList()
			.flatMap(ids -> buildAdjacency(ids, threshold)
				.map(adj -> {
					final var comp = bfsFrom(id, adj);
					final String repr = chooseRepresentative(comp, adj);
					return new Cluster(comp, repr);
				})
			);
	}

	private Mono<Map<String, List<NewsSimilarityStore.SimilarItem>>> buildAdjacency(final List<String> ids, final double threshold) {
		return Flux.fromIterable(ids)
			.flatMap(id -> store.neighbors(id, threshold).collectList().map(list -> Map.entry(id, list)))
			.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	private List<Cluster> computeClusters(final List<String> ids, final Map<String, List<NewsSimilarityStore.SimilarItem>> adj) {
		final var visited = new HashSet<String>();
		final List<Cluster> clusters = new ArrayList<>();
		for (final String start : ids) {
			if (!visited.add(start)) continue;
			final Set<String> comp = bfsFrom(start, adj, visited);
			final String repr = chooseRepresentative(comp, adj);
			clusters.add(new Cluster(comp, repr));
		}
		return clusters;
	}

	private Set<String> bfsFrom(final String start, final Map<String, List<NewsSimilarityStore.SimilarItem>> adj) {
		return bfsFrom(start, adj, new HashSet<>());
	}

	private Set<String> bfsFrom(final String start, final Map<String, List<NewsSimilarityStore.SimilarItem>> adj, final Set<String> globalVisited) {
		final Set<String> comp = new HashSet<>();
		final ArrayDeque<String> q = new ArrayDeque<>();
		q.add(start);
		globalVisited.add(start);
		comp.add(start);
		while (!q.isEmpty()) {
			final String cur = q.poll();
			final var neighbors = adj.getOrDefault(cur, List.of());
			for (final var n : neighbors) {
				final String nid = n.id();
				if (globalVisited.add(nid)) {
					comp.add(nid);
					q.add(nid);
				}
			}
		}
		return comp;
	}

	private String chooseRepresentative(final Set<String> ids, final Map<String, List<NewsSimilarityStore.SimilarItem>> adj) {
		String best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		final double eps = 1e-12;
		for (final String id : ids) {
			double sum = 0d;
			for (final var n : adj.getOrDefault(id, List.of())) {
				if (ids.contains(n.id())) sum += n.score();
			}
			final boolean strictlyBetter = sum > bestScore + eps;
			final boolean tie = Math.abs(sum - bestScore) <= eps;
			if (strictlyBetter || (tie && (best == null || id.compareTo(best) < 0))) {
				bestScore = sum;
				best = id;
			}
		}
		return best;
	}
}
