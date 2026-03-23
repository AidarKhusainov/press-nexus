package com.nexus.press.app.news.policy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;

public final class NewsClusterGraph {

	private NewsClusterGraph() {
	}

	public static List<NewsCluster> computeClusters(
		final List<String> ids,
		final Map<String, List<SimilarNewsNeighbor>> adjacency
	) {
		final Set<String> visited = new HashSet<>();
		final List<NewsCluster> clusters = new ArrayList<>();
		for (final String start : ids) {
			if (!visited.add(start)) {
				continue;
			}
			final Set<String> component = bfsFrom(start, adjacency, visited);
			final String representativeId = chooseRepresentative(component, adjacency);
			clusters.add(new NewsCluster(component, representativeId));
		}
		return clusters;
	}

	public static NewsCluster clusterOf(
		final String id,
		final Map<String, List<SimilarNewsNeighbor>> adjacency
	) {
		final Set<String> component = bfsFrom(id, adjacency);
		final String representativeId = chooseRepresentative(component, adjacency);
		return new NewsCluster(component, representativeId);
	}

	private static Set<String> bfsFrom(
		final String start,
		final Map<String, List<SimilarNewsNeighbor>> adjacency
	) {
		return bfsFrom(start, adjacency, new HashSet<>());
	}

	private static Set<String> bfsFrom(
		final String start,
		final Map<String, List<SimilarNewsNeighbor>> adjacency,
		final Set<String> globalVisited
	) {
		final Set<String> component = new HashSet<>();
		final ArrayDeque<String> queue = new ArrayDeque<>();
		queue.add(start);
		globalVisited.add(start);
		component.add(start);
		while (!queue.isEmpty()) {
			final String current = queue.poll();
			final List<SimilarNewsNeighbor> neighbors = adjacency.getOrDefault(current, List.of());
			for (final SimilarNewsNeighbor neighbor : neighbors) {
				final String neighborId = neighbor.id();
				if (globalVisited.add(neighborId)) {
					component.add(neighborId);
					queue.add(neighborId);
				}
			}
		}
		return component;
	}

	private static String chooseRepresentative(
		final Set<String> ids,
		final Map<String, List<SimilarNewsNeighbor>> adjacency
	) {
		String best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		final double eps = 1e-12;
		for (final String id : ids) {
			double sum = 0d;
			for (final SimilarNewsNeighbor neighbor : adjacency.getOrDefault(id, List.of())) {
				if (ids.contains(neighbor.id())) {
					sum += neighbor.score();
				}
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
