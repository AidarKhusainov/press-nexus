package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.news.model.ClusterReportCluster;
import com.nexus.press.app.news.model.ClusterReportNewsItem;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.NewsPipelineDebugReport;
import com.nexus.press.app.news.model.PipelineRuntimeCounters;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import com.nexus.press.app.news.policy.NewsClusterGraph;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BuildNewsPipelineDebugReport {

	private final PostgresNewsPipelineQuery newsPipelineQuery;
	private final NewsSimilarityRepository newsSimilarityRepository;
	private final NewsPipelineProperties newsPipelineProperties;
	private final SimilarityProperties similarityProperties;
	private final PipelineRuntimeStats pipelineRuntimeStats;

	public Mono<NewsPipelineDebugReport> execute() {
		final OffsetDateTime generatedAt = OffsetDateTime.now();
		final PipelineRuntimeCounters counters = pipelineRuntimeStats.snapshot();

		return newsPipelineQuery.loadSummaryReadyNews(
				newsPipelineProperties.getClaimTimeout(),
				newsPipelineProperties.getSummaryMaturity()
			)
			.collectList()
			.flatMap(newsItems -> buildReport(generatedAt, counters, newsItems));
	}

	private Mono<NewsPipelineDebugReport> buildReport(
		final OffsetDateTime generatedAt,
		final PipelineRuntimeCounters counters,
		final List<ClusterReportNewsItem> newsItems
	) {
		if (newsItems.isEmpty()) {
			return Mono.just(new NewsPipelineDebugReport(
				generatedAt,
				null,
				null,
				counters,
				0,
				0,
				List.of()
			));
		}

		final Map<String, ClusterReportNewsItem> newsById = newsItems.stream()
			.collect(java.util.stream.Collectors.toMap(ClusterReportNewsItem::id, item -> item));
		final List<String> ids = newsItems.stream()
			.map(ClusterReportNewsItem::id)
			.toList();
		final Set<String> recentIds = new HashSet<>(ids);

		return loadAdjacency(ids, recentIds)
			.map(adjacency -> NewsClusterGraph.computeClusters(ids, adjacency))
			.map(clusters -> toReport(generatedAt, counters, clusters, newsById));
	}

	private Mono<Map<String, List<com.nexus.press.app.news.model.SimilarNewsNeighbor>>> loadAdjacency(
		final List<String> ids,
		final Set<String> recentIds
	) {
		final double threshold = similarityProperties.getClusterMinScore();
		return Flux.fromIterable(ids)
			.flatMap(id -> newsSimilarityRepository.neighbors(id, threshold)
				.filter(neighbor -> recentIds.contains(neighbor.id()))
				.collectList()
				.map(neighbors -> Map.entry(id, neighbors)))
			.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	private NewsPipelineDebugReport toReport(
		final OffsetDateTime generatedAt,
		final PipelineRuntimeCounters counters,
		final List<NewsCluster> clusters,
		final Map<String, ClusterReportNewsItem> newsById
	) {
		final int minClusterSize = Math.max(1, newsPipelineProperties.getDebugReportMinClusterSize());
		final int maxClusterSize = Math.max(minClusterSize, newsPipelineProperties.getDebugReportMaxClusterSize());
		final int topClustersLimit = Math.max(1, newsPipelineProperties.getDebugReportTopClusters());

		final Comparator<ClusterReportCluster> clusterComparator = Comparator
			.comparingInt(ClusterReportCluster::size)
			.reversed()
			.thenComparing(ClusterReportCluster::representativeEventAt, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(ClusterReportCluster::representativeTitle, Comparator.nullsLast(String::compareToIgnoreCase));

		final List<ClusterReportCluster> allClusterCards = clusters.stream()
			.map(cluster -> toClusterCard(cluster, newsById))
			.filter(cluster -> cluster != null)
			.toList();

		final List<ClusterReportCluster> topClusters = allClusterCards.stream()
			.filter(cluster -> cluster.size() >= minClusterSize && cluster.size() <= maxClusterSize)
			.sorted(clusterComparator)
			.limit(topClustersLimit)
			.toList();

		final int matchedClusters = (int) allClusterCards.stream()
			.filter(cluster -> cluster.size() >= minClusterSize && cluster.size() <= maxClusterSize)
			.count();

		return new NewsPipelineDebugReport(
			generatedAt,
			null,
			null,
			counters,
			allClusterCards.size(),
			matchedClusters,
			topClusters
		);
	}

	private ClusterReportCluster toClusterCard(
		final NewsCluster cluster,
		final Map<String, ClusterReportNewsItem> newsById
	) {
		final List<ClusterReportNewsItem> items = cluster.ids().stream()
			.map(newsById::get)
			.filter(java.util.Objects::nonNull)
			.sorted(Comparator.comparing(ClusterReportNewsItem::eventAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(ClusterReportNewsItem::title, Comparator.nullsLast(String::compareToIgnoreCase)))
			.toList();
		if (items.isEmpty()) {
			return null;
		}

		final ClusterReportNewsItem representative = newsById.get(cluster.representativeId());
		final ClusterReportNewsItem fallbackRepresentative = representative != null ? representative : items.getFirst();

		return new ClusterReportCluster(
			fallbackRepresentative.id(),
			fallbackRepresentative.title(),
			fallbackRepresentative.eventAt(),
			items.size(),
			items
		);
	}
}
