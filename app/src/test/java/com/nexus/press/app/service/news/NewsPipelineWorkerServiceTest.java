package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.ProcessedNews;
import com.nexus.press.app.service.news.model.RawNews;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsPipelineWorkerServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void drainOnceProcessesClaimedStagesAndMarksDuplicateSummaryDone() {
		final var persistence = new StubPersistenceService(
			List.of(raw("content-1")),
			List.of(raw("embedding-1")),
			List.of(raw("repr-1"), raw("dup-1"))
		);
		final var populateService = mock(NewsPopulateContentService.class);
		final var embeddingService = mock(NewsEmbeddingService.class);
		final var summarizationService = mock(NewsSummarizationService.class);
		final var clusteringService = mock(NewsClusteringService.class);
		final var properties = pipelineProperties();
		final var similarityProperties = new SimilarityProperties();

		when(populateService.populate(any(RawNews.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			final List<RawNews> batch = invocation.getArgument(0);
			return Mono.just(batch.stream().map(NewsPipelineWorkerServiceTest::processed).toList());
		});
		when(clusteringService.clusterOf(eq("repr-1"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsClusteringService.Cluster(java.util.Set.of("repr-1", "dup-1"), "repr-1")));
		when(clusteringService.clusterOf(eq("dup-1"), eq(similarityProperties.getClusterMinScore())))
			.thenReturn(Mono.just(new NewsClusteringService.Cluster(java.util.Set.of("repr-1", "dup-1"), "repr-1")));
		when(summarizationService.summarize(any(ProcessedNews.class)))
			.thenAnswer(invocation -> Mono.just(((ProcessedNews) invocation.getArgument(0)).withContentSummary("summary")));

		final var worker = new NewsPipelineWorkerService(
			persistence,
			populateService,
			embeddingService,
			summarizationService,
			clusteringService,
			properties,
			similarityProperties,
			APP_METRICS
		);

		final var result = worker.drainOnce().block();

		assertEquals(1L, result.contentClaimed());
		assertEquals(1L, result.embeddingClaimed());
		assertEquals(2L, result.summaryClaimed());
		assertEquals(List.of("dup-1:DONE"), persistence.summaryStatusUpdates);
		verify(populateService).populate(any(RawNews.class));
		verify(embeddingService).embedBatch(any());
		verify(summarizationService).summarize(any(ProcessedNews.class));
	}

	@Test
	void drainOnceClaimsEmbeddingWorkInParallelBatches() {
		final var persistence = new StubPersistenceService(
			List.of(),
			List.of(raw("embedding-1"), raw("embedding-2"), raw("embedding-3"), raw("embedding-4"), raw("embedding-5")),
			List.of()
		);
		final var populateService = mock(NewsPopulateContentService.class);
		final var embeddingService = mock(NewsEmbeddingService.class);
		final var summarizationService = mock(NewsSummarizationService.class);
		final var clusteringService = mock(NewsClusteringService.class);
		final var properties = pipelineProperties();
		properties.setEmbeddingBatchSize(2);
		properties.setEmbeddingConcurrency(2);

		when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			final List<RawNews> batch = invocation.getArgument(0);
			return Mono.just(batch.stream().map(NewsPipelineWorkerServiceTest::processed).toList());
		});

		final var worker = new NewsPipelineWorkerService(
			persistence,
			populateService,
			embeddingService,
			summarizationService,
			clusteringService,
			properties,
			new SimilarityProperties(),
			APP_METRICS
		);

		final var result = worker.drainOnce().block();
		final ArgumentCaptor<List<RawNews>> captor = ArgumentCaptor.forClass(List.class);

		assertEquals(4L, result.embeddingClaimed());
		verify(embeddingService, times(2)).embedBatch(captor.capture());
		assertEquals(List.of(2, 2), captor.getAllValues().stream().map(List::size).toList());
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

	private static final class StubPersistenceService extends NewsPersistenceService {

		private final List<RawNews> contentBatch;
		private final List<RawNews> embeddingBatch;
		private final List<RawNews> summaryBatch;
		private final List<String> summaryStatusUpdates = new ArrayList<>();

		private StubPersistenceService(
			final List<RawNews> contentBatch,
			final List<RawNews> embeddingBatch,
			final List<RawNews> summaryBatch
		) {
			super(null);
			this.contentBatch = contentBatch;
			this.embeddingBatch = embeddingBatch;
			this.summaryBatch = summaryBatch;
		}

		@Override
		public Flux<RawNews> claimNewsPendingContent(final int limit, final Duration claimTimeout) {
			return Flux.fromIterable(contentBatch).take(limit);
		}

		@Override
		public Flux<RawNews> claimNewsPendingEmbedding(final int limit, final Duration claimTimeout) {
			return Flux.fromIterable(embeddingBatch).take(limit);
		}

		@Override
		public Flux<RawNews> claimNewsPendingSummary(final int limit, final Duration claimTimeout) {
			return Flux.fromIterable(summaryBatch).take(limit);
		}

		@Override
		public Mono<PipelineBacklogSnapshot> loadPipelineBacklog() {
			return Mono.just(new PipelineBacklogSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
		}

		@Override
		public Mono<Void> updateStatusSummary(final String id, final ProcessingStatus status) {
			summaryStatusUpdates.add(id + ":" + status.name());
			return Mono.empty();
		}
	}
}
