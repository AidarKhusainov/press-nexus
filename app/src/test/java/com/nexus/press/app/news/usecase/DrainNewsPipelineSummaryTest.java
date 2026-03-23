package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.NewsPipelineBacklogSnapshot;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.news.usecase.InheritNewsSummary;
import com.nexus.press.app.news.usecase.SummarizeNews;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.MonoSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DrainNewsPipelineSummaryTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void executeProcessesRepresentativesAndDuplicates() {
		final var newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final var newsRepository = mock(NewsRepository.class);
		final var findNewsCluster = mock(FindNewsCluster.class);
		final var summarizeNews = mock(SummarizeNews.class);
		final var inheritNewsSummary = mock(InheritNewsSummary.class);
		final var similarityProperties = new SimilarityProperties();

		when(newsPipelineQuery.claimNewsPendingSummary(anyInt(), any(), any()))
			.thenReturn(Flux.just(raw("repr-1"), raw("dup-1")));
		when(newsPipelineQuery.loadPipelineBacklog())
			.thenReturn(Mono.just(emptyBacklog()));
		when(findNewsCluster.execute(eq("repr-1"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsCluster(java.util.Set.of("repr-1", "dup-1"), "repr-1")));
		when(findNewsCluster.execute(eq("dup-1"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsCluster(java.util.Set.of("repr-1", "dup-1"), "repr-1")));
		when(summarizeNews.execute(any(ProcessedNews.class), any(), any()))
			.thenAnswer(invocation -> Mono.just(((ProcessedNews) invocation.getArgument(0)).withContentSummary("summary")));
		when(inheritNewsSummary.execute(any(ProcessedNews.class), eq("repr-1")))
			.thenAnswer(invocation -> Mono.just(((ProcessedNews) invocation.getArgument(0)).withContentSummary("summary")));

		final var useCase = new DrainNewsPipelineSummary(
			newsPipelineQuery,
			newsRepository,
			findNewsCluster,
			summarizeNews,
			inheritNewsSummary,
			pipelineProperties(),
			similarityProperties,
			APP_METRICS
		);

		final var result = useCase.execute().block();

		assertEquals(0L, result.contentClaimed());
		assertEquals(0L, result.embeddingClaimed());
		assertEquals(2L, result.summaryClaimed());
		verify(summarizeNews).execute(any(ProcessedNews.class), any(), any());
		verify(inheritNewsSummary).execute(any(ProcessedNews.class), eq("repr-1"));
		verifyNoMoreInteractions(summarizeNews, inheritNewsSummary);
	}

	@Test
	void executePreservesConfiguredSummaryConcurrencyForRepresentatives() throws Exception {
		final var newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final var newsRepository = mock(NewsRepository.class);
		final var findNewsCluster = mock(FindNewsCluster.class);
		final var summarizeNews = mock(SummarizeNews.class);
		final var inheritNewsSummary = mock(InheritNewsSummary.class);
		final var properties = pipelineProperties();
		properties.setSummaryConcurrency(2);
		final var similarityProperties = new SimilarityProperties();

		when(newsPipelineQuery.claimNewsPendingSummary(anyInt(), any(), any()))
			.thenReturn(Flux.just(raw("repr-1"), raw("repr-2")));
		when(newsPipelineQuery.loadPipelineBacklog())
			.thenReturn(Mono.just(emptyBacklog()));
		when(findNewsCluster.execute(eq("repr-1"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsCluster(java.util.Set.of("repr-1"), "repr-1")));
		when(findNewsCluster.execute(eq("repr-2"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsCluster(java.util.Set.of("repr-2"), "repr-2")));

		final CountDownLatch started = new CountDownLatch(2);
		final CountDownLatch finished = new CountDownLatch(1);
		final AtomicInteger active = new AtomicInteger();
		final AtomicInteger maxActive = new AtomicInteger();
		final Map<String, MonoSink<ProcessedNews>> sinks = new ConcurrentHashMap<>();

		when(summarizeNews.execute(any(ProcessedNews.class), any(), any()))
			.thenAnswer(invocation -> {
				final ProcessedNews news = invocation.getArgument(0);
				return Mono.<ProcessedNews>create(sink -> {
					sinks.put(news.getId(), sink);
					final int concurrent = active.incrementAndGet();
					maxActive.accumulateAndGet(concurrent, Math::max);
					started.countDown();
				}).doFinally(signalType -> active.decrementAndGet());
			});

		final var useCase = new DrainNewsPipelineSummary(
			newsPipelineQuery,
			newsRepository,
			findNewsCluster,
			summarizeNews,
			inheritNewsSummary,
			properties,
			similarityProperties,
			APP_METRICS
		);

		final Disposable subscription = useCase.execute().doFinally(signal -> finished.countDown()).subscribe();
		try {
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertEquals(2, maxActive.get());

			sinks.get("repr-1").success(processed(raw("repr-1")).withContentSummary("summary"));
			sinks.get("repr-2").success(processed(raw("repr-2")).withContentSummary("summary"));

			assertTrue(finished.await(2, TimeUnit.SECONDS));
		} finally {
			subscription.dispose();
		}
	}

	private static NewsPipelineProperties pipelineProperties() {
		final var properties = new NewsPipelineProperties();
		properties.setClaimTimeout(Duration.ofMinutes(30));
		properties.setContentBatchSize(4);
		properties.setEmbeddingBatchSize(4);
		properties.setSummaryBatchSize(4);
		properties.setPopulateConcurrency(2);
		properties.setEmbeddingConcurrency(2);
		properties.setSummaryConcurrency(2);
		return properties;
	}

	private static NewsPipelineBacklogSnapshot emptyBacklog() {
		return new NewsPipelineBacklogSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	private static RawNews raw(final String id) {
		return RawNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description " + id)
			.rawContent("Raw content " + id)
			.cleanContent("Clean content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language("en")
			.build();
	}

	private static ProcessedNews processed(final RawNews news) {
		return ProcessedNews.builder()
			.id(news.getId())
			.link(news.getLink())
			.title(news.getTitle())
			.description(news.getDescription())
			.rawContent(news.getRawContent())
			.cleanContent(news.getCleanContent())
			.source(news.getSource())
			.publishedDate(news.getPublishedDate())
			.language(news.getLanguage())
			.build();
	}
}
