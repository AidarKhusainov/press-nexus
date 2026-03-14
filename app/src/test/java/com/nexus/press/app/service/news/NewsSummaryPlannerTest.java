package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.config.property.SummarizationProperties;
import com.nexus.press.app.service.ai.summ.SummarizationProvider;
import com.nexus.press.app.service.ai.summ.SummarizationUseCase;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.ProcessedNews;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NewsSummaryPlannerTest {

	@Test
		void planForRepresentativeReusesCachedSummaryBeforeAnyRanking() {
		final NewsPersistenceService persistence = mock(NewsPersistenceService.class);
		final NewsClusteringService clusteringService = mock(NewsClusteringService.class);
		when(persistence.findReusableSummary(any(), any(), any()))
			.thenReturn(Mono.just(new NewsPersistenceService.CachedSummary("GEMINI:model", "ru", "Cached summary")));

		final var planner = new NewsSummaryPlanner(
			persistence,
			clusteringService,
			similarityProperties(),
			summarizationProperties(12, 5, 3, 12, Duration.ofMinutes(15))
		);

		final var plan = planner.planForRepresentative(
			sampleNews("rep-1", OffsetDateTime.now().minusHours(2)),
			new NewsClusteringService.Cluster(Set.of("rep-1"), "rep-1"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(NewsSummaryPlanner.PlanType.REUSE_CACHED, plan.type());
		assertEquals("cache hit", plan.reason());
		assertEquals("Cached summary", plan.cachedSummary().summary());
		verifyNoInteractions(clusteringService);
	}

	@Test
	void planForRepresentativeDefersImmatureAutoCluster() {
		final NewsPersistenceService persistence = mock(NewsPersistenceService.class);
		when(persistence.findReusableSummary(any(), any(), any())).thenReturn(Mono.empty());

		final var planner = new NewsSummaryPlanner(
			persistence,
			mock(NewsClusteringService.class),
			similarityProperties(),
			summarizationProperties(12, 5, 3, 12, Duration.ofMinutes(15))
		);

		final var plan = planner.planForRepresentative(
			sampleNews("rep-2", OffsetDateTime.now().minusMinutes(5)),
			new NewsClusteringService.Cluster(Set.of("rep-2"), "rep-2"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(NewsSummaryPlanner.PlanType.DEFER, plan.type());
		assertEquals("cluster not mature", plan.reason());
	}

	@Test
	void planForRepresentativeUsesFallbackWhenClusterIsOutsideDailyTopN() {
		final NewsPersistenceService persistence = mock(NewsPersistenceService.class);
		final NewsClusteringService clusteringService = mock(NewsClusteringService.class);
		when(persistence.findReusableSummary(any(), any(), any())).thenReturn(Mono.empty());
		when(persistence.loadSummaryPriorityCandidates(any()))
			.thenReturn(Flux.just(
				new NewsPersistenceService.SummaryPriorityCandidate(
					"rep-1",
					Media.RBK.name(),
					OffsetDateTime.now().minusHours(1)
				),
				new NewsPersistenceService.SummaryPriorityCandidate(
					"rep-2",
					Media.BBC.name(),
					OffsetDateTime.now().minusHours(3)
				)
			));
		when(clusteringService.buildClusters(anyDouble()))
			.thenReturn(Mono.just(List.of(
				new NewsClusteringService.Cluster(Set.of("rep-1", "dup-1"), "rep-1"),
				new NewsClusteringService.Cluster(Set.of("rep-2"), "rep-2")
			)));

		final var planner = new NewsSummaryPlanner(
			persistence,
			clusteringService,
			similarityProperties(),
			summarizationProperties(12, 5, 3, 1, Duration.ofMinutes(15))
		);

		final var plan = planner.planForRepresentative(
			sampleNews("rep-2", OffsetDateTime.now().minusHours(2)),
			new NewsClusteringService.Cluster(Set.of("rep-2"), "rep-2"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(NewsSummaryPlanner.PlanType.USE_FALLBACK, plan.type());
		assertEquals("outside auto-cluster topN", plan.reason());
	}

	@Test
	void planForRepresentativeStopsUsingProviderWhenBudgetIsExhausted() {
		final NewsPersistenceService persistence = mock(NewsPersistenceService.class);
		final NewsClusteringService clusteringService = mock(NewsClusteringService.class);
		when(persistence.findReusableSummary(any(), any(), any())).thenReturn(Mono.empty());
		when(persistence.loadSummaryPriorityCandidates(any()))
			.thenReturn(Flux.just(new NewsPersistenceService.SummaryPriorityCandidate(
				"rep-3",
				Media.RIA.name(),
				OffsetDateTime.now().minusHours(1)
			)));
		when(clusteringService.buildClusters(anyDouble()))
			.thenReturn(Mono.just(List.of(new NewsClusteringService.Cluster(Set.of("rep-3", "dup-3"), "rep-3"))));

		final var planner = new NewsSummaryPlanner(
			persistence,
			clusteringService,
			similarityProperties(),
			summarizationProperties(1, 5, 3, 12, Duration.ofMinutes(15))
		);
		final ProcessedNews news = sampleNews("rep-3", OffsetDateTime.now().minusHours(2));
		final NewsClusteringService.Cluster cluster = new NewsClusteringService.Cluster(Set.of("rep-3", "dup-3"), "rep-3");

		final var firstPlan = planner.planForRepresentative(news, cluster, "ru", SummarizationUseCase.AUTO_CLUSTER).block();
		final var secondPlan = planner.planForRepresentative(news, cluster, "ru", SummarizationUseCase.AUTO_CLUSTER).block();

		assertEquals(NewsSummaryPlanner.PlanType.USE_PROVIDER, firstPlan.type());
		assertEquals(NewsSummaryPlanner.PlanType.USE_FALLBACK, secondPlan.type());
		assertEquals("budget exhausted", secondPlan.reason());
	}

	private static SimilarityProperties similarityProperties() {
		final var properties = new SimilarityProperties();
		properties.setClusterMinScore(0.95);
		return properties;
	}

	private static SummarizationProperties summarizationProperties(
		final int autoClusterDailyBudget,
		final int userFacingDailyBudget,
		final int reserveDailyBudget,
		final int autoClusterTopNPerDay,
		final Duration autoClusterMaturity
	) {
		return new SummarizationProperties(
			SummarizationProvider.GEMINI,
			List.of(SummarizationProvider.GROQ, SummarizationProvider.MISTRAL),
			autoClusterDailyBudget,
			userFacingDailyBudget,
			reserveDailyBudget,
			autoClusterTopNPerDay,
			autoClusterMaturity
		);
	}

	private static ProcessedNews sampleNews(final String id, final OffsetDateTime publishedAt) {
		return ProcessedNews.builder()
			.id(id)
			.title("Title " + id)
			.cleanContent("Clean content " + id)
			.source(Media.BBC)
			.publishedDate(publishedAt)
			.fetchedDate(publishedAt.plusMinutes(1))
			.contentHash("hash-" + id)
			.language("ru")
			.build();
	}
}
