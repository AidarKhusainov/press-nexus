package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsEmbeddingVector;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.ai.integration.embed.EmbeddingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbedNewsTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());
	private static final PipelineRuntimeStats PIPELINE_RUNTIME_STATS = new PipelineRuntimeStats();

	@Test
	void embedPersistsVectorAndDropsItFromProcessedPayload() {
		final var embeddingService = new StubEmbeddingService(new float[] {1f, 2f, 3f});
		final var similarityStore = new RecordingSimilarityStore();
		final var newsRepository = mock(NewsRepository.class);
		final var statusHistory = new ArrayList<ProcessingStatus>();
		when(newsRepository.updateStatusEmbedding(anyString(), any())).thenAnswer(invocation -> {
			statusHistory.add(invocation.getArgument(1));
			return Mono.empty();
		});
		final var service = new EmbedNews(
			embeddingService,
			similarityStore,
			newsRepository,
			APP_METRICS,
			PIPELINE_RUNTIME_STATS
		);

		final var result = service.execute(sampleRawNews("id-1")).block();

		assertNotNull(result);
		assertEquals(List.of(ProcessingStatus.IN_PROGRESS, ProcessingStatus.DONE), statusHistory);
		assertEquals("id-1", similarityStore.lastId);
		assertEquals(3, similarityStore.lastEmbedding.length);
		assertEquals("Clean content id-1", embeddingService.lastText);
		assertNull(result.getContentSummary());
	}

	@Test
	void embedBatchPersistsEachVectorAndMarksItemsDone() {
		final var embeddingService = new StubEmbeddingService(List.of(
			new float[] {1f, 0f, 0f},
			new float[] {0f, 1f, 0f}
		));
		final var similarityStore = new RecordingSimilarityStore();
		final var newsRepository = mock(NewsRepository.class);
		final var statusHistory = new ArrayList<ProcessingStatus>();
		when(newsRepository.updateStatusEmbedding(anyString(), any())).thenAnswer(invocation -> {
			statusHistory.add(invocation.getArgument(1));
			return Mono.empty();
		});
		final var single = new EmbedNews(
			embeddingService,
			similarityStore,
			newsRepository,
			APP_METRICS,
			PIPELINE_RUNTIME_STATS
		);
		final var service = new EmbedNewsBatch(embeddingService, single, APP_METRICS);

		final var result = service.execute(List.of(sampleRawNews("id-1"), sampleRawNews("id-2"))).block();

		assertNotNull(result);
		assertEquals(2, result.size());
		assertEquals(List.of(ProcessingStatus.DONE, ProcessingStatus.DONE), statusHistory);
		assertEquals(List.of("id-1", "id-2"), similarityStore.ids);
		assertEquals(2, similarityStore.embeddings.size());
		assertEquals(List.of("Clean content id-1", "Clean content id-2"), embeddingService.lastBatchTexts);
	}

	private static RawNews sampleRawNews(final String id) {
		return RawNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description")
			.rawContent("Raw content " + id)
			.cleanContent("Clean content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language("en")
			.build();
	}

	private static final class StubEmbeddingService implements EmbeddingService {

		private final float[] embedding;
		private final List<float[]> embeddings;
		private String lastText;
		private List<String> lastBatchTexts = List.of();

		private StubEmbeddingService(final float[] embedding) {
			this.embedding = embedding;
			this.embeddings = List.of();
		}

		private StubEmbeddingService(final List<float[]> embeddings) {
			this.embedding = new float[0];
			this.embeddings = embeddings;
		}

		@Override
		public Mono<float[]> embed(final String text) {
			lastText = text;
			return Mono.just(embedding);
		}

		@Override
		public Mono<List<float[]>> embedBatch(final List<String> texts) {
			lastBatchTexts = texts;
			return Mono.just(embeddings);
		}
	}

	private static final class RecordingSimilarityStore implements NewsSimilarityRepository {

		private String lastId;
		private float[] lastEmbedding;
		private final List<String> ids = new ArrayList<>();
		private final List<float[]> embeddings = new ArrayList<>();

		@Override
		public Flux<SimilarNewsNeighbor> topSimilar(final String id, final int topN, final double minScore) {
			return Flux.empty();
		}

		@Override
		public Mono<Void> upsertEmbedding(final String id, final float[] embedding) {
			lastId = id;
			lastEmbedding = embedding;
			ids.add(id);
			embeddings.add(embedding);
			return Mono.empty();
		}

		@Override
		public Flux<NewsEmbeddingVector> allEmbeddings() {
			return Flux.empty();
		}

		@Override
		public Flux<String> allIds() {
			return Flux.empty();
		}

		@Override
		public Flux<SimilarNewsNeighbor> neighbors(final String id, final double minScore) {
			return Flux.empty();
		}
	}
}
