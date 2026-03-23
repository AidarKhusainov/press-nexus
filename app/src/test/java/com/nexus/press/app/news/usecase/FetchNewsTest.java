package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.integration.NewsFetchProcessor;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FetchNewsTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void fetchNewsIgnoresAlreadySavedDiscoveredNews() {
		final var processor = mock(NewsFetchProcessor.class);
		final var newsRepository = mock(NewsRepository.class);
		final var service = new FetchNews(
			List.of(processor),
			newsRepository,
			new NewsPipelineProperties(),
			APP_METRICS,
			new PipelineRuntimeStats()
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
		when(newsRepository.saveDiscoveredIfAbsent(any(NewsUpsertRequest.class), anyString(), any()))
			.thenReturn(Mono.empty());

		final var persisted = service.execute().collectList().block(Duration.ofSeconds(5));

		assertNotNull(persisted);
		assertTrue(persisted.isEmpty());
		verify(newsRepository).saveDiscoveredIfAbsent(any(NewsUpsertRequest.class), anyString(), any());
	}
}
