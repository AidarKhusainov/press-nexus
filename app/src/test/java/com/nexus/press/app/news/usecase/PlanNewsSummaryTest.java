package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.config.property.SummarizationProperties;
import com.nexus.press.app.news.model.CachedNewsSummary;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.NewsSummaryPriorityCandidate;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.ai.model.SummarizationProvider;
import com.nexus.press.app.ai.model.SummarizationUseCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlanNewsSummaryTest {

	@Test
	void planForRepresentativeReusesCachedSummaryBeforeAnyRanking() {
		final NewsRepository newsRepository = mock(NewsRepository.class);
		final PostgresNewsPipelineQuery newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final BuildNewsClusters buildNewsClusters = mock(BuildNewsClusters.class);
		when(newsRepository.findReusableSummary(any(), any()))
			.thenReturn(Mono.just(new CachedNewsSummary("GEMINI:model", "ru", "Cached summary")));

		final var planner = new PlanNewsSummary(
			newsRepository,
			newsPipelineQuery,
			buildNewsClusters,
			similarityProperties(),
			summarizationProperties(12, 5, 3, 12, Duration.ofMinutes(15))
		);

		final var plan = planner.execute(
			sampleNews("rep-1", OffsetDateTime.now().minusHours(2)),
			new NewsCluster(Set.of("rep-1"), "rep-1"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(PlanNewsSummary.PlanType.REUSE_CACHED, plan.type());
		assertEquals("cache hit", plan.reason());
		assertEquals("Cached summary", plan.cachedSummary().summary());
		verifyNoInteractions(newsPipelineQuery, buildNewsClusters);
	}

	@Test
	void planForRepresentativeDefersImmatureAutoCluster() {
		final NewsRepository newsRepository = mock(NewsRepository.class);
		when(newsRepository.findReusableSummary(any(), any())).thenReturn(Mono.empty());

		final var planner = new PlanNewsSummary(
			newsRepository,
			mock(PostgresNewsPipelineQuery.class),
			mock(BuildNewsClusters.class),
			similarityProperties(),
			summarizationProperties(12, 5, 3, 12, Duration.ofMinutes(15))
		);

		final var plan = planner.execute(
			sampleNews("rep-2", OffsetDateTime.now().minusMinutes(5)),
			new NewsCluster(Set.of("rep-2"), "rep-2"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(PlanNewsSummary.PlanType.DEFER, plan.type());
		assertEquals("cluster not mature", plan.reason());
	}

	@Test
	void planForRepresentativeUsesFallbackWhenClusterIsOutsideDailyTopN() {
		final NewsRepository newsRepository = mock(NewsRepository.class);
		final PostgresNewsPipelineQuery newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final BuildNewsClusters buildNewsClusters = mock(BuildNewsClusters.class);
		when(newsRepository.findReusableSummary(any(), any())).thenReturn(Mono.empty());
		when(newsPipelineQuery.loadSummaryPriorityCandidates(any()))
			.thenReturn(Flux.just(
				new NewsSummaryPriorityCandidate(
					"rep-1",
					Media.RBK.name(),
					OffsetDateTime.now().minusHours(1)
				),
				new NewsSummaryPriorityCandidate(
					"rep-2",
					Media.BBC.name(),
					OffsetDateTime.now().minusHours(3)
				)
			));
		when(buildNewsClusters.execute(anyDouble()))
			.thenReturn(Mono.just(List.of(
				new NewsCluster(Set.of("rep-1", "dup-1"), "rep-1"),
				new NewsCluster(Set.of("rep-2"), "rep-2")
			)));

		final var planner = new PlanNewsSummary(
			newsRepository,
			newsPipelineQuery,
			buildNewsClusters,
			similarityProperties(),
			summarizationProperties(12, 5, 3, 1, Duration.ofMinutes(15))
		);

		final var plan = planner.execute(
			sampleNews("rep-2", OffsetDateTime.now().minusHours(2)),
			new NewsCluster(Set.of("rep-2"), "rep-2"),
			"ru",
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertEquals(PlanNewsSummary.PlanType.USE_FALLBACK, plan.type());
		assertEquals("outside auto-cluster topN", plan.reason());
	}

	@Test
	void planForRepresentativeStopsUsingProviderWhenBudgetIsExhausted() {
		final NewsRepository newsRepository = mock(NewsRepository.class);
		final PostgresNewsPipelineQuery newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final BuildNewsClusters buildNewsClusters = mock(BuildNewsClusters.class);
		when(newsRepository.findReusableSummary(any(), any())).thenReturn(Mono.empty());
		when(newsPipelineQuery.loadSummaryPriorityCandidates(any()))
			.thenReturn(Flux.just(new NewsSummaryPriorityCandidate(
				"rep-3",
				Media.RIA.name(),
				OffsetDateTime.now().minusHours(1)
			)));
		when(buildNewsClusters.execute(anyDouble()))
			.thenReturn(Mono.just(List.of(new NewsCluster(Set.of("rep-3", "dup-3"), "rep-3"))));

		final var planner = new PlanNewsSummary(
			newsRepository,
			newsPipelineQuery,
			buildNewsClusters,
			similarityProperties(),
			summarizationProperties(1, 5, 3, 12, Duration.ofMinutes(15))
		);
		final ProcessedNews news = sampleNews("rep-3", OffsetDateTime.now().minusHours(2));
		final NewsCluster cluster = new NewsCluster(Set.of("rep-3", "dup-3"), "rep-3");

		final var firstPlan = planner.execute(news, cluster, "ru", SummarizationUseCase.AUTO_CLUSTER).block();
		final var secondPlan = planner.execute(news, cluster, "ru", SummarizationUseCase.AUTO_CLUSTER).block();

		assertEquals(PlanNewsSummary.PlanType.USE_PROVIDER, firstPlan.type());
		assertEquals(PlanNewsSummary.PlanType.USE_FALLBACK, secondPlan.type());
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
			.language("ru")
			.build();
	}
}
