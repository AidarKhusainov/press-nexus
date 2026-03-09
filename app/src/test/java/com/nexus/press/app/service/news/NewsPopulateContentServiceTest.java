package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsPopulateContentProcessor;
import com.nexus.press.app.service.queue.NewsPopulateContentQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsPopulateContentServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void populateContinuesWhenProcessorFailsAndPersistsFallbackContent() {
		final var source = sampleNews("id-1", "fallback content");
		final var queue = new RecordingQueue();
		final var persistence = new StubPersistenceService();
		final var processor = new NewsPopulateContentProcessor() {
			@Override
			public java.util.Set<Media> getSupportedMedia() {
				return java.util.Set.of(Media.BBC);
			}

			@Override
			public Mono<RawNews> process(final RawNews news) {
				return Mono.error(new RuntimeException("parser error"));
			}
		};
		final var service = new NewsPopulateContentService(List.of(processor), queue, persistence, APP_METRICS);

		final var result = service.populate(source).block();

		assertNotNull(result);
		assertEquals("fallback content", result.getRawContent());
		assertNotNull(persistence.lastUpsertRequest);
		assertEquals("fallback content", persistence.lastUpsertRequest.getContentRaw());
		assertEquals(ProcessingStatus.DONE, persistence.lastUpsertRequest.getStatusContent());
		assertEquals(ProcessingStatus.PENDING, persistence.lastUpsertRequest.getStatusEmbedding());
		assertEquals(ProcessingStatus.PENDING, persistence.lastUpsertRequest.getStatusSummary());
		assertEquals(1, queue.added.size());
		assertEquals(source.getId(), queue.added.getFirst().getId());
		assertNull(persistence.updatedStatusId);
	}

	@Test
	void populateMarksContentFailedWhenPersistenceFails() {
		final var source = sampleNews("id-2", "fallback content");
		final var queue = new RecordingQueue();
		final var persistence = new StubPersistenceService();
		persistence.upsertError = new RuntimeException("db error");
		final var processor = new NewsPopulateContentProcessor() {
			@Override
			public java.util.Set<Media> getSupportedMedia() {
				return java.util.Set.of(Media.BBC);
			}

			@Override
			public Mono<RawNews> process(final RawNews news) {
				return Mono.just(news.withRawContent("full content"));
			}
		};
		final var service = new NewsPopulateContentService(List.of(processor), queue, persistence, APP_METRICS);

		final var result = service.populate(source).block();

		assertNull(result);
		assertEquals(source.getId(), persistence.updatedStatusId);
		assertEquals(ProcessingStatus.FAILED, persistence.updatedStatus);
		assertEquals(0, queue.added.size());
	}

	@Test
	void populateUsesDescriptionAsFallbackWhenNoProcessorRegistered() {
		final var queue = new RecordingQueue();
		final var persistence = new StubPersistenceService();
		final var serviceWithoutProcessor = new NewsPopulateContentService(List.of(), queue, persistence, APP_METRICS);
		final var source = sampleNews("id-3", "plain description");

		final var result = serviceWithoutProcessor.populate(source).block();

		assertNotNull(result);
		assertEquals("plain description", result.getRawContent());
		assertEquals("plain description", persistence.lastUpsertRequest.getContentRaw());
		assertEquals(1, queue.added.size());
		assertEquals(source.getId(), queue.added.getFirst().getId());
	}

	@Test
	void populateUsesHigherPriorityProcessorFirst() {
		final var source = sampleNews("id-4", "fallback");
		final var queue = new RecordingQueue();
		final var persistence = new StubPersistenceService();
		final var calls = new ArrayList<String>();

		final var genericProcessor = new NewsPopulateContentProcessor() {
			@Override
			public java.util.Set<Media> getSupportedMedia() {
				return java.util.Set.of(Media.BBC);
			}

			@Override
			public int getPriority() {
				return -100;
			}

			@Override
			public Mono<RawNews> process(final RawNews news) {
				calls.add("generic");
				return Mono.just(news.withRawContent("generic"));
			}
		};

		final var specificProcessor = new NewsPopulateContentProcessor() {
			@Override
			public java.util.Set<Media> getSupportedMedia() {
				return java.util.Set.of(Media.BBC);
			}

			@Override
			public int getPriority() {
				return 200;
			}

			@Override
			public Mono<RawNews> process(final RawNews news) {
				calls.add("specific");
				return Mono.just(news.withRawContent("specific"));
			}
		};

		final var service = new NewsPopulateContentService(List.of(genericProcessor, specificProcessor), queue, persistence, APP_METRICS);
		final var result = service.populate(source).block();

		assertNotNull(result);
		assertEquals("specific", result.getRawContent());
		assertEquals(List.of("specific"), calls);
	}

	private static RawNews sampleNews(final String id, final String description) {
		return RawNews.builder()
			.id(id)
			.link("https://example.com/news/" + id)
			.title("Title " + id)
			.description(description)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language("en")
			.build();
	}

	private static final class StubPersistenceService extends NewsPersistenceService {

		private NewsUpsertRequest lastUpsertRequest;
		private RuntimeException upsertError;
		private String updatedStatusId;
		private ProcessingStatus updatedStatus;

		private StubPersistenceService() {
			super(null);
		}

		@Override
		public Mono<NewsEntity> upsert(final NewsUpsertRequest req) {
			lastUpsertRequest = req;
			if (upsertError != null) {
				return Mono.error(upsertError);
			}
			return Mono.just(NewsEntity.builder().id(req.getId()).build());
		}

		@Override
		public Mono<Void> updateStatusContent(final String id, final ProcessingStatus status) {
			updatedStatusId = id;
			updatedStatus = status;
			return Mono.empty();
		}
	}

	private static final class RecordingQueue extends NewsPopulateContentQueue {

		private final List<RawNews> added = new ArrayList<>();

		private RecordingQueue() {
			super(APP_METRICS);
		}

		@Override
		public void add(final RawNews news) {
			added.add(news);
		}
	}
}
