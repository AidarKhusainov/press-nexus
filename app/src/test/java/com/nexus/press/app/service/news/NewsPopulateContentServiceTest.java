package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsPopulateContentProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsPopulateContentServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void populateContinuesWhenProcessorFailsAndPersistsFallbackContent() {
		final var source = sampleNews("id-1", "fallback content");
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
		final var service = new NewsPopulateContentService(List.of(processor), new NewsContentCleaner(), persistence, APP_METRICS);

		final var result = service.populate(source).block();

		assertNotNull(result);
		assertEquals("fallback content", result.getRawContent());
		assertEquals("Title id-1\n\nfallback content", result.getCleanContent());
		assertNotNull(persistence.lastUpsertRequest);
		assertEquals("fallback content", persistence.lastUpsertRequest.getContentRaw());
		assertEquals("Title id-1\n\nfallback content", persistence.lastUpsertRequest.getContentClean());
		assertEquals(ProcessingStatus.DONE, persistence.lastUpsertRequest.getStatusContent());
		assertEquals(ProcessingStatus.PENDING, persistence.lastUpsertRequest.getStatusEmbedding());
		assertEquals(ProcessingStatus.PENDING, persistence.lastUpsertRequest.getStatusSummary());
		assertNull(persistence.updatedStatusId);
	}

	@Test
	void populateMarksContentFailedWhenPersistenceFails() {
		final var source = sampleNews("id-2", "fallback content");
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
		final var service = new NewsPopulateContentService(List.of(processor), new NewsContentCleaner(), persistence, APP_METRICS);

		final var result = service.populate(source).block();

		assertNull(result);
		assertEquals(source.getId(), persistence.updatedStatusId);
		assertEquals(ProcessingStatus.FAILED, persistence.updatedStatus);
	}

	@Test
	void populateUsesDescriptionAsFallbackWhenNoProcessorRegistered() {
		final var persistence = new StubPersistenceService();
		final var serviceWithoutProcessor = new NewsPopulateContentService(List.of(), new NewsContentCleaner(), persistence, APP_METRICS);
		final var source = sampleNews("id-3", "plain description");

		final var result = serviceWithoutProcessor.populate(source).block();

		assertNotNull(result);
		assertEquals("plain description", result.getRawContent());
		assertEquals("plain description", persistence.lastUpsertRequest.getContentRaw());
		assertEquals("Title id-3\n\nplain description", persistence.lastUpsertRequest.getContentClean());
	}

	@Test
	void populateUsesHigherPriorityProcessorFirst() {
		final var source = sampleNews("id-4", "fallback");
		final var persistence = new StubPersistenceService();
		final var calls = new java.util.ArrayList<String>();

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

		final var service = new NewsPopulateContentService(List.of(genericProcessor, specificProcessor), new NewsContentCleaner(), persistence, APP_METRICS);
		final var result = service.populate(source).block();

		assertNotNull(result);
		assertEquals("specific", result.getRawContent());
		assertEquals(List.of("specific"), calls);
	}

	@Test
	void populateBuildsCleanContentWithoutBoilerplateTail() {
		final var source = sampleNews("id-5", "fallback");
		final var persistence = new StubPersistenceService();
		final var processor = new NewsPopulateContentProcessor() {
			@Override
			public java.util.Set<Media> getSupportedMedia() {
				return java.util.Set.of(Media.BBC);
			}

			@Override
			public Mono<RawNews> process(final RawNews news) {
				return Mono.just(news.withRawContent(
					"Lead paragraph with actual article text and enough detail to keep the cleaner focused.\n\n"
						+ "Second paragraph continues the story with more substance and context for the reader.\n\n"
						+ "Читайте также\n\n"
						+ "Еще один посторонний блок"
				));
			}
		};

		final var service = new NewsPopulateContentService(List.of(processor), new NewsContentCleaner(), persistence, APP_METRICS);

		final var result = service.populate(source).block();

		assertNotNull(result);
		assertEquals(
			"Lead paragraph with actual article text and enough detail to keep the cleaner focused.\n\n"
				+ "Second paragraph continues the story with more substance and context for the reader.",
			result.getCleanContent()
		);
		assertEquals(result.getCleanContent(), persistence.lastUpsertRequest.getContentClean());
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
}
