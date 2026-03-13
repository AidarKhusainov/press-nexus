package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiSummarizationServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void summarizeUsesSupportedGeminiRequestShape() {
		final AtomicReference<String> path = new AtomicReference<>();
		final AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> {
				path.set(request.url().getPath());
				headers.set(request.headers());
				assertNull(request.url().getQuery());
				return jsonResponse("""
					{
					  "candidates": [
					    {
					      "content": {
					        "parts": [
					          { "text": "Первая фраза." },
					          { "text": "Вторая фраза." }
					        ]
					      }
					    }
					  ]
					}
					""");
			}),
			new GeminiProperties(null, "gemini-key", "gemini-2.5-flash")
		);

		final String summary = service.summarize("Исходный текст", "ru").block();

		assertEquals("/models/gemini-2.5-flash:generateContent", path.get());
		assertEquals("gemini-key", headers.get().getFirst("x-goog-api-key"));
		assertEquals("press-nexus/0.1.0", headers.get().getFirst("x-goog-api-client"));
		assertEquals("Первая фраза. Вторая фраза.", summary);
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

	private static HttpClientProperties defaultProperties() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"https://generativelanguage.googleapis.com/v1beta",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(HttpClientName.GEMINI, cfg));
	}

	private static Mono<ClientResponse> jsonResponse(final String body) {
		return Mono.just(
			ClientResponse.create(HttpStatusCode.valueOf(200))
				.header("Content-Type", "application/json")
				.body(body)
				.build()
		);
	}
}
