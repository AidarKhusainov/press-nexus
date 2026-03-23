package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsPipelineBacklogSnapshot;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.usecase.EmbedNewsBatch;
import com.nexus.press.app.news.usecase.PopulateNewsContent;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DrainNewsPipelineIngestionTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void executeProcessesClaimedContentAndEmbeddingStages() {
		final var newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final var populateService = mock(PopulateNewsContent.class);
		final var embeddingService = mock(EmbedNewsBatch.class);

		when(newsPipelineQuery.claimNewsPendingContent(anyInt(), any()))
			.thenReturn(Flux.just(raw("content-1")));
		when(newsPipelineQuery.claimNewsPendingEmbedding(anyInt(), any()))
			.thenReturn(Flux.just(raw("embedding-1")));
		when(newsPipelineQuery.loadPipelineBacklog())
			.thenReturn(Mono.just(emptyBacklog()));
		when(populateService.execute(any(RawNews.class)))
			.thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		when(embeddingService.execute(any()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				final List<RawNews> batch = invocation.getArgument(0);
				return Mono.just(batch.stream().map(DrainNewsPipelineIngestionTest::processed).toList());
			});

		final var useCase = new DrainNewsPipelineIngestion(
			newsPipelineQuery,
			populateService,
			embeddingService,
			pipelineProperties(),
			APP_METRICS
		);

		final var result = useCase.execute().block();

		assertEquals(1L, result.contentClaimed());
		assertEquals(1L, result.embeddingClaimed());
		assertEquals(0L, result.summaryClaimed());
		verify(populateService).execute(any(RawNews.class));
		verify(embeddingService).execute(any());
	}

	@Test
	void executeClaimsEmbeddingWorkInParallelBatches() {
		final var newsPipelineQuery = mock(PostgresNewsPipelineQuery.class);
		final var populateService = mock(PopulateNewsContent.class);
		final var embeddingService = mock(EmbedNewsBatch.class);
		final var properties = pipelineProperties();
		properties.setEmbeddingBatchSize(2);
		properties.setEmbeddingConcurrency(2);

		when(newsPipelineQuery.claimNewsPendingContent(anyInt(), any()))
			.thenReturn(Flux.empty());
		when(newsPipelineQuery.claimNewsPendingEmbedding(anyInt(), any()))
			.thenAnswer(invocation -> Flux.just(
				raw("embedding-1"),
				raw("embedding-2"),
				raw("embedding-3"),
				raw("embedding-4"),
				raw("embedding-5")
			).take(((Integer) invocation.getArgument(0)).longValue()));
		when(newsPipelineQuery.loadPipelineBacklog())
			.thenReturn(Mono.just(emptyBacklog()));
		when(embeddingService.execute(any()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				final List<RawNews> batch = invocation.getArgument(0);
				return Mono.just(batch.stream().map(DrainNewsPipelineIngestionTest::processed).toList());
			});

		final var useCase = new DrainNewsPipelineIngestion(
			newsPipelineQuery,
			populateService,
			embeddingService,
			properties,
			APP_METRICS
		);

		final var result = useCase.execute().block();
		final ArgumentCaptor<List<RawNews>> captor = ArgumentCaptor.forClass(List.class);

		assertEquals(4L, result.embeddingClaimed());
		verify(embeddingService, times(2)).execute(captor.capture());
		assertEquals(List.of(2, 2), captor.getAllValues().stream().map(List::size).toList());
		verifyNoInteractions(populateService);
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
