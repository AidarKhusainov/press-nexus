package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import java.time.Duration;
import java.time.OffsetDateTime;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.news.model.ClusterReportNewsItem;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildNewsPipelineDebugReportTest {

	@Test
	void executeBuildsTopClustersFromSummaryReadyBacklog() {
		final var pipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final var similarityRepository = mock(NewsSimilarityRepository.class);
		final var properties = new NewsPipelineProperties();
		properties.setClaimTimeout(Duration.ofMinutes(30));
		properties.setSummaryMaturity(Duration.ofMinutes(15));
		properties.setDebugReportMinClusterSize(2);
		properties.setDebugReportMaxClusterSize(3);
		properties.setDebugReportTopClusters(10);
		final var similarityProperties = new SimilarityProperties();
		similarityProperties.setClusterMinScore(0.95);
		final var runtimeStats = new PipelineRuntimeStats();
		runtimeStats.recordDiscoveredNews();
		runtimeStats.recordPopulatedNews();
		runtimeStats.recordEmbeddedNews();

		final var first = new ClusterReportNewsItem(
			"news-1",
			"First",
			"https://example.com/1",
			"BBC",
			OffsetDateTime.parse("2026-03-23T09:00:00Z")
		);
		final var second = new ClusterReportNewsItem(
			"news-2",
			"Second",
			"https://example.com/2",
			"BBC",
			OffsetDateTime.parse("2026-03-23T08:55:00Z")
		);
		final var third = new ClusterReportNewsItem(
			"news-3",
			"Third",
			"https://example.com/3",
			"REUTERS",
			OffsetDateTime.parse("2026-03-23T08:00:00Z")
		);

		when(pipelineQuery.loadSummaryReadyNews(any(), any()))
			.thenReturn(Flux.just(first, second, third));
		when(similarityRepository.neighbors(eq("news-1"), eq(0.95)))
			.thenReturn(Flux.just(new com.nexus.press.app.news.model.SimilarNewsNeighbor("news-2", 0.99)));
		when(similarityRepository.neighbors(eq("news-2"), eq(0.95)))
			.thenReturn(Flux.just(new com.nexus.press.app.news.model.SimilarNewsNeighbor("news-1", 0.99)));
		when(similarityRepository.neighbors(eq("news-3"), eq(0.95)))
			.thenReturn(Flux.empty());

		final var useCase = new BuildNewsPipelineDebugReport(
			pipelineQuery,
			similarityRepository,
			properties,
			similarityProperties,
			runtimeStats
		);

		final var report = useCase.execute().block();

		assertEquals(1L, report.counters().discoveredNews());
		assertEquals(1L, report.counters().populatedNews());
		assertEquals(1L, report.counters().embeddedNews());
		assertEquals(2, report.totalRecentClusters());
		assertEquals(1, report.matchedClusters());
		assertEquals(1, report.topClusters().size());
		assertEquals(2, report.topClusters().getFirst().size());
		assertFalse(report.topClusters().getFirst().items().isEmpty());
		assertNull(report.lookbackFrom());
		assertEquals(1L, runtimeStats.snapshot().discoveredNews());
	}
}
