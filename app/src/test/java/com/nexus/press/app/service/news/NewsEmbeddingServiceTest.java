package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.ai.embed.EmbeddingService;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsEmbeddingServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void embedPersistsVectorAndDropsItFromProcessedPayload() {
		final var embeddingService = new StubEmbeddingService(new float[] { 1f, 2f, 3f });
		final var similarityStore = new RecordingSimilarityStore();
		final var persistence = new RecordingPersistenceService();
		final var service = new NewsEmbeddingService(embeddingService, similarityStore, persistence, APP_METRICS);

		final var result = service.embed(sampleRawNews("id-1")).block();

		assertNotNull(result);
		assertEquals(List.of(ProcessingStatus.IN_PROGRESS, ProcessingStatus.DONE), persistence.statusHistory);
		assertEquals("id-1", similarityStore.lastId);
		assertEquals(3, similarityStore.lastEmbedding.length);
		assertNull(result.getContentSummary());
	}

	@Test
	void embedBatchPersistsEachVectorAndMarksItemsDone() {
		final var embeddingService = new StubEmbeddingService(List.of(
			new float[] { 1f, 0f, 0f },
			new float[] { 0f, 1f, 0f }
		));
		final var similarityStore = new RecordingSimilarityStore();
		final var persistence = new RecordingPersistenceService();
		final var service = new NewsEmbeddingService(embeddingService, similarityStore, persistence, APP_METRICS);

		final var result = service.embedBatch(List.of(sampleRawNews("id-1"), sampleRawNews("id-2"))).block();

		assertNotNull(result);
		assertEquals(2, result.size());
		assertEquals(List.of(ProcessingStatus.DONE, ProcessingStatus.DONE), persistence.statusHistory);
		assertEquals(List.of("id-1", "id-2"), similarityStore.ids);
		assertEquals(2, similarityStore.embeddings.size());
	}

	private static RawNews sampleRawNews(final String id) {
		return RawNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description")
			.rawContent("Raw content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language("en")
			.build();
	}

	private static final class StubEmbeddingService implements EmbeddingService {

		private final float[] embedding;
		private final List<float[]> embeddings;

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
			return Mono.just(embedding);
		}

		@Override
		public Mono<List<float[]>> embedBatch(final List<String> texts) {
			return Mono.just(embeddings);
		}
	}

	private static final class RecordingSimilarityStore implements ReactiveNewsSimilarityStore {

		private String lastId;
		private float[] lastEmbedding;
		private final List<String> ids = new ArrayList<>();
		private final List<float[]> embeddings = new ArrayList<>();

		@Override
		public Mono<Void> put(final String idA, final String idB, final double similarity) {
			return Mono.empty();
		}

		@Override
		public Flux<NewsSimilarityStore.SimilarItem> topSimilar(final String id, final int topN, final double minScore) {
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
		public Flux<NewsSimilarityStore.EmbeddingItem> allEmbeddings() {
			return Flux.empty();
		}

		@Override
		public Flux<String> allIds() {
			return Flux.empty();
		}

		@Override
		public Flux<NewsSimilarityStore.SimilarItem> neighbors(final String id, final double minScore) {
			return Flux.empty();
		}
	}

	private static final class RecordingPersistenceService extends NewsPersistenceService {

		private final List<ProcessingStatus> statusHistory = new ArrayList<>();

		private RecordingPersistenceService() {
			super(null);
		}

		@Override
		public Mono<Void> updateStatusEmbedding(final String id, final ProcessingStatus status) {
			statusHistory.add(status);
			return Mono.empty();
		}
	}
}
