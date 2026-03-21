package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsFetchProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsFetchServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void fetchNewsIgnoresAlreadySavedDiscoveredNews() {
		final var processor = mock(NewsFetchProcessor.class);
		final var persistenceService = mock(NewsPersistenceService.class);
		final var service = new NewsFetchService(
			List.of(processor),
			persistenceService,
			new NewsPipelineProperties(),
			APP_METRICS
		);
		final var news = RawNews.builder()
			.id("news-1")
			.externalId("bbc-1")
			.link("https://example.com/news-1")
			.title("Title")
			.description("Description")
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-03-20T12:00:00Z"))
			.language("en")
			.build();

		when(processor.fetchNews()).thenReturn(Flux.just(news));
		when(persistenceService.saveDiscoveredIfAbsent(any())).thenReturn(Mono.empty());

		final var persisted = service.fetchNews().collectList().block(Duration.ofSeconds(5));

		assertNotNull(persisted);
		assertTrue(persisted.isEmpty());
		verify(persistenceService).saveDiscoveredIfAbsent(any());
	}
}
