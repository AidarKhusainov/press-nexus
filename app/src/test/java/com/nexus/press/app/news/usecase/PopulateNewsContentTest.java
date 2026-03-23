package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.integration.NewsPopulateContentProcessor;
import com.nexus.press.app.news.policy.NewsContentCleaner;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PopulateNewsContentTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void populateContinuesWhenProcessorFailsAndPersistsFallbackContent() {
		final var source = sampleNews("id-1", "fallback content");
		final var upsertNews = mock(UpsertNews.class);
		final var newsRepository = mock(NewsRepository.class);
		final var capturedRequest = new AtomicReference<NewsUpsertRequest>();
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
		when(upsertNews.execute(any(NewsUpsertRequest.class))).thenAnswer(invocation -> {
			final NewsUpsertRequest request = invocation.getArgument(0);
			capturedRequest.set(request);
			return Mono.just(request.getId());
		});

		final var service = new PopulateNewsContent(
			List.of(processor),
			new NewsContentCleaner(),
			upsertNews,
			newsRepository,
			APP_METRICS
		);

		final var result = service.execute(source).block();

		assertNotNull(result);
		assertEquals("fallback content", result.getRawContent());
		assertEquals("Title id-1\n\nfallback content", result.getCleanContent());
		assertNotNull(capturedRequest.get());
		assertEquals("fallback content", capturedRequest.get().getContentRaw());
		assertEquals("Title id-1\n\nfallback content", capturedRequest.get().getContentClean());
		assertEquals(ProcessingStatus.DONE, capturedRequest.get().getStatusContent());
		assertEquals(ProcessingStatus.PENDING, capturedRequest.get().getStatusEmbedding());
		assertEquals(ProcessingStatus.PENDING, capturedRequest.get().getStatusSummary());
	}

	@Test
	void populateMarksContentFailedWhenPersistenceFails() {
		final var source = sampleNews("id-2", "fallback content");
		final var upsertNews = mock(UpsertNews.class);
		final var newsRepository = mock(NewsRepository.class);
		final var updatedStatusId = new AtomicReference<String>();
		final var updatedStatus = new AtomicReference<ProcessingStatus>();
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
		when(upsertNews.execute(any(NewsUpsertRequest.class))).thenReturn(Mono.error(new RuntimeException("db error")));
		when(newsRepository.updateStatusContent(eq(source.getId()), any())).thenAnswer(invocation -> {
			updatedStatusId.set(invocation.getArgument(0));
			updatedStatus.set(invocation.getArgument(1));
			return Mono.empty();
		});

		final var service = new PopulateNewsContent(
			List.of(processor),
			new NewsContentCleaner(),
			upsertNews,
			newsRepository,
			APP_METRICS
		);

		final var result = service.execute(source).block();

		assertNull(result);
		assertEquals(source.getId(), updatedStatusId.get());
		assertEquals(ProcessingStatus.FAILED, updatedStatus.get());
	}

	@Test
	void populateUsesDescriptionAsFallbackWhenNoProcessorRegistered() {
		final var upsertNews = mock(UpsertNews.class);
		final var newsRepository = mock(NewsRepository.class);
		final var capturedRequest = new AtomicReference<NewsUpsertRequest>();
		when(upsertNews.execute(any(NewsUpsertRequest.class))).thenAnswer(invocation -> {
			final NewsUpsertRequest request = invocation.getArgument(0);
			capturedRequest.set(request);
			return Mono.just(request.getId());
		});

		final var service = new PopulateNewsContent(List.of(), new NewsContentCleaner(), upsertNews, newsRepository, APP_METRICS);
		final var source = sampleNews("id-3", "plain description");

		final var result = service.execute(source).block();

		assertNotNull(result);
		assertEquals("plain description", result.getRawContent());
		assertEquals("plain description", capturedRequest.get().getContentRaw());
		assertEquals("Title id-3\n\nplain description", capturedRequest.get().getContentClean());
	}

	@Test
	void populateUsesHigherPriorityProcessorFirst() {
		final var source = sampleNews("id-4", "fallback");
		final var upsertNews = mock(UpsertNews.class);
		final var newsRepository = mock(NewsRepository.class);
		final var calls = new java.util.ArrayList<String>();

		when(upsertNews.execute(any(NewsUpsertRequest.class))).thenAnswer(invocation -> {
			final NewsUpsertRequest request = invocation.getArgument(0);
			return Mono.just(request.getId());
		});

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

		final var service = new PopulateNewsContent(
			List.of(genericProcessor, specificProcessor),
			new NewsContentCleaner(),
			upsertNews,
			newsRepository,
			APP_METRICS
		);
		final var result = service.execute(source).block();

		assertNotNull(result);
		assertEquals("specific", result.getRawContent());
		assertEquals(List.of("specific"), calls);
	}

	@Test
	void populateBuildsCleanContentWithoutBoilerplateTail() {
		final var source = sampleNews("id-5", "fallback");
		final var upsertNews = mock(UpsertNews.class);
		final var newsRepository = mock(NewsRepository.class);
		final var capturedRequest = new AtomicReference<NewsUpsertRequest>();
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
		when(upsertNews.execute(any(NewsUpsertRequest.class))).thenAnswer(invocation -> {
			final NewsUpsertRequest request = invocation.getArgument(0);
			capturedRequest.set(request);
			return Mono.just(request.getId());
		});

		final var service = new PopulateNewsContent(
			List.of(processor),
			new NewsContentCleaner(),
			upsertNews,
			newsRepository,
			APP_METRICS
		);

		final var result = service.execute(source).block();

		assertNotNull(result);
		assertEquals(
			"Lead paragraph with actual article text and enough detail to keep the cleaner focused.\n\n"
				+ "Second paragraph continues the story with more substance and context for the reader.",
			result.getCleanContent()
		);
		assertEquals(result.getCleanContent(), capturedRequest.get().getContentClean());
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
}
