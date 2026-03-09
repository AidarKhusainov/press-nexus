package com.nexus.press.app.service.news.platform;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericPopulateContentProcessorTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void processExtractsMainArticleText() {
		final var p1 = "This is a long paragraph for extraction testing. ".repeat(14);
		final var p2 = "Second paragraph keeps the article body large enough. ".repeat(12);
		final var html = "<html><body><script>console.log('x')</script><article><p>" + p1 + "</p><p>" + p2 + "</p></article></body></html>";
		final var processor = new GenericPopulateContentProcessor(stubWebClientConfig((request) -> okResponse(html)));
		final var source = rawNews("https://example.com/ok", "<p>fallback</p>");

		final var result = processor.process(source).block();

		assertNotNull(result);
		assertNotNull(result.getRawContent());
		assertTrue(result.getRawContent().contains("This is a long paragraph"));
		assertTrue(result.getRawContent().contains("Second paragraph"));
		assertFalse(result.getRawContent().contains("console.log"));
	}

	@Test
	void processFallsBackToDescriptionWhenHtmlIsBlank() {
		final var processor = new GenericPopulateContentProcessor(stubWebClientConfig((request) -> okResponse("")));
		final var source = rawNews("https://example.com/blank", "<p>Fallback <b>description</b></p>");

		final var result = processor.process(source).block();

		assertNotNull(result);
		assertEquals("Title\n\nFallback description", result.getRawContent());
	}

	@Test
	void processFallsBackToDescriptionOnHttpError() {
		final var processor = new GenericPopulateContentProcessor(stubWebClientConfig((request) -> errorResponse(500, "server error")));
		final var source = rawNews("https://example.com/error", "<p>Error fallback</p>");

		final var result = processor.process(source).block();

		assertNotNull(result);
		assertEquals("Title\n\nError fallback", result.getRawContent());
	}

	@Test
	void supportedMediaContainsInternationalAndRussianSources() {
		final var supported = new GenericPopulateContentProcessor(stubWebClientConfig((request) -> okResponse(""))).getSupportedMedia();

		assertEquals(Media.values().length, supported.size());
		assertTrue(supported.contains(Media.BBC));
		assertTrue(supported.contains(Media.REUTERS));
		assertTrue(supported.contains(Media.CNN));
		assertTrue(supported.contains(Media.FOXNEWS));
		assertTrue(supported.contains(Media.GUARDIAN));
		assertTrue(supported.contains(Media.NPR));
		assertTrue(supported.contains(Media.ALJAZEERA));
		assertTrue(supported.contains(Media.ABC));
		assertTrue(supported.contains(Media.CBS));
		assertTrue(supported.contains(Media.DW));
		assertTrue(supported.contains(Media.TASS));
		assertTrue(supported.contains(Media.RBK));
		assertTrue(supported.contains(Media.KOMMERSANT));
		assertTrue(supported.contains(Media.LENTA));
		assertTrue(supported.contains(Media.GAZETA));
		assertTrue(supported.contains(Media.IZVESTIA));
		assertTrue(supported.contains(Media.VEDOMOSTI));
		assertTrue(supported.contains(Media.INTERFAX));
		assertTrue(supported.contains(Media.MEDUZA));
		assertTrue(supported.contains(Media.NOVAYA_GAZETA));
		assertTrue(supported.contains(Media.RIA));
		assertTrue(supported.contains(Media.NYTIMES));
	}

	private static RawNews rawNews(final String link, final String description) {
		return RawNews.builder()
			.id("news-1")
			.link(link)
			.title("Title")
			.description(description)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T10:00:00Z"))
			.language("en")
			.build();
	}

	private static HttpClientProperties defaultProperties() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"https://example.com",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(HttpClientName.NEWS, cfg));
	}

	private static WebClientConfig stubWebClientConfig(final ExchangeFunction exchangeFunction) {
		final var webClient = WebClient.builder()
			.exchangeFunction(exchangeFunction)
			.build();
		return new WebClientConfig(defaultProperties(), APP_METRICS) {
			@Override
			public WebClient getWebClient(final HttpClientName clientName) {
				return webClient;
			}
		};
	}

	private static Mono<ClientResponse> okResponse(final String body) {
		return Mono.just(
			ClientResponse.create(HttpStatusCode.valueOf(200))
				.header("Content-Type", "text/html; charset=utf-8")
				.body(body)
				.build()
		);
	}

	private static Mono<ClientResponse> errorResponse(final int status, final String body) {
		return Mono.just(
			ClientResponse.create(HttpStatusCode.valueOf(status))
				.header("Content-Type", "text/plain; charset=utf-8")
				.body(body)
				.build()
		);
	}
}
