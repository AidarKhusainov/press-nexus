package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.ProcessedNews;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.queue.NewsEmbeddingQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewsEmbeddingRecoveryServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void recoverPendingEmbeddingsProcessesOnlyRequestedBatchSize() {
		final var pending = List.of(
			raw("id-1"),
			raw("id-2"),
			raw("id-3")
		);
		final var persistence = new StubPersistenceService(pending);
		final var embedding = new StubEmbeddingService(Set.of());
		final var service = new NewsEmbeddingRecoveryService(persistence, embedding, APP_METRICS);

		final long processed = service.recoverPendingEmbeddings(2).block();

		assertEquals(2L, processed);
		assertEquals(List.of("id-1", "id-2"), embedding.processedIds);
	}

	@Test
	void recoverPendingEmbeddingsSkipsFailedItemsAndContinues() {
		final var pending = List.of(
			raw("ok-1"),
			raw("bad-1"),
			raw("ok-2")
		);
		final var persistence = new StubPersistenceService(pending);
		final var embedding = new StubEmbeddingService(Set.of("bad-1"));
		final var service = new NewsEmbeddingRecoveryService(persistence, embedding, APP_METRICS);

		final long processed = service.recoverPendingEmbeddings(10).block();

		assertEquals(2L, processed);
		assertEquals(List.of("ok-1", "bad-1", "ok-2"), embedding.processedIds);
	}

	private static RawNews raw(final String id) {
		return RawNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description")
			.rawContent("Content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T10:00:00Z"))
			.language("en")
			.build();
	}

	private static final class StubPersistenceService extends NewsPersistenceService {

		private final List<RawNews> pending;

		private StubPersistenceService(final List<RawNews> pending) {
			super(null);
			this.pending = pending;
		}

		@Override
		public Flux<RawNews> findNewsPendingEmbedding(final int limit) {
			return Flux.fromIterable(pending).take(limit);
		}
	}

	private static final class StubEmbeddingService extends NewsEmbeddingService {

		private final Set<String> failIds;
		private final List<String> processedIds = new ArrayList<>();

		private StubEmbeddingService(final Set<String> failIds) {
			super(null, new NewsEmbeddingQueue(APP_METRICS), new NewsPersistenceService(null), APP_METRICS);
			this.failIds = failIds;
		}

		@Override
		public Mono<ProcessedNews> embed(final RawNews rawNews) {
			processedIds.add(rawNews.getId());
			if (failIds.contains(rawNews.getId())) {
				return Mono.error(new RuntimeException("failed"));
			}
			return Mono.just(ProcessedNews.builder()
				.id(rawNews.getId())
				.link(rawNews.getLink())
				.title(rawNews.getTitle())
				.description(rawNews.getDescription())
				.rawContent(rawNews.getRawContent())
				.source(rawNews.getSource())
				.publishedDate(rawNews.getPublishedDate())
				.language(rawNews.getLanguage())
				.contentEmbedding(new float[] { 1f })
				.build());
		}
	}
}
