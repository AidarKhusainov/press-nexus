package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			properties(1_000, 1_000, 12_000)
		);

		final String summary = service.summarize("Исходный текст", "ru").block();

		assertEquals("/models/gemini-2.5-flash:generateContent", path.get());
		assertEquals("gemini-key", headers.get().getFirst("x-goog-api-key"));
		assertEquals("press-nexus/0.1.0", headers.get().getFirst("x-goog-api-client"));
		assertEquals("Первая фраза. Вторая фраза.", summary);
	}

	@Test
	void summarizeStopsWhenDailyBudgetIsExhausted() {
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> jsonResponse("""
				{
				  "candidates": [
				    {
				      "content": {
				        "parts": [
				          { "text": "ok" }
				        ]
				      }
				    }
				  ]
				}
				""")),
			properties(1_000, 1, 12_000)
		);

		assertEquals("ok", service.summarize("Первый текст", "ru").block());

		final var ex = assertThrows(
			SummarizationThrottledException.class,
			() -> service.summarize("Второй текст", "ru").block()
		);

		assertTrue(ex.getMessage().contains("daily budget exhausted"));
	}

	@Test
	void summarizeUsesResilience4jRateLimiterForPerMinuteBudget() {
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> jsonResponse("""
				{
				  "candidates": [
				    {
				      "content": {
				        "parts": [
				          { "text": "ok" }
				        ]
				      }
				    }
				  ]
				}
				""")),
			properties(1, 1_000, 12_000)
		);

		assertEquals("ok", service.summarize("Первый текст", "ru").block());

		final var ex = assertThrows(
			SummarizationThrottledException.class,
			() -> service.summarize("Второй текст", "ru").block()
		);

		assertTrue(ex.getMessage().contains("rate limiter"));
	}

	@Test
	void summarizeOpensCircuitBreakerAfterRepeated429Responses() {
		final AtomicInteger calls = new AtomicInteger();
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> {
				calls.incrementAndGet();
				return response(429, """
					{
					  "error": {
					    "message": "quota exceeded"
					  }
					}
					""");
			}),
			properties(1_000, 1_000, 12_000)
		);

		for (int i = 0; i < 4; i++) {
			final int attempt = i;
			assertThrows(
				SummarizationThrottledException.class,
				() -> service.summarize("Текст " + attempt, "ru").block()
			);
		}

		final var ex = assertThrows(
			SummarizationThrottledException.class,
			() -> service.summarize("Текст 5", "ru").block()
		);

		assertTrue(ex.getMessage().contains("circuit breaker"));
		assertEquals(4, calls.get());
	}

	@Test
	void summarizeDoesNotBurnDailyBudgetWhenCircuitBreakerRejectsLocally() {
		final AtomicInteger calls = new AtomicInteger();
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> {
				calls.incrementAndGet();
				return response(429, """
					{
					  "error": {
					    "message": "quota exceeded"
					  }
					}
					""");
			}),
			properties(1_000, 5, 12_000)
		);

		for (int i = 0; i < 4; i++) {
			final int attempt = i;
			assertThrows(
				SummarizationThrottledException.class,
				() -> service.summarize("Текст " + attempt, "ru").block()
			);
		}

		final var fifth = assertThrows(
			SummarizationThrottledException.class,
			() -> service.summarize("Текст 5", "ru").block()
		);
		final var sixth = assertThrows(
			SummarizationThrottledException.class,
			() -> service.summarize("Текст 6", "ru").block()
		);

		assertTrue(fifth.getMessage().contains("circuit breaker"));
		assertTrue(sixth.getMessage().contains("circuit breaker"));
		assertEquals(4, calls.get());
	}

	@Test
	void summarizeIncludesResponseBodyForBadRequest() {
		final var service = new GeminiSummarizationService(
			stubWebClientConfig(request -> response(400, """
				{
				  "error": {
				    "message": "Request payload too large"
				  }
				}
				""")),
			properties(1_000, 1_000, 16)
		);

		final var ex = assertThrows(
			IllegalStateException.class,
			() -> service.summarize("Очень длинный текст для усечения и диагностики", "ru").block()
		);

		assertTrue(ex.getMessage().contains("Gemini bad request"));
		assertTrue(ex.getMessage().contains("Request payload too large"));
		assertTrue(ex.getMessage().contains("inputChars=16"));
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

	private static GeminiProperties properties(
		final int maxRequestsPerMinute,
		final int maxRequestsPerDay,
		final int maxInputChars
	) {
		return new GeminiProperties(
			null,
			"gemini-key",
			"gemini-2.5-flash",
			false,
			maxRequestsPerMinute,
			maxRequestsPerDay,
			maxInputChars
		);
	}

	private static Mono<ClientResponse> jsonResponse(final String body) {
		return response(200, body);
	}

	private static Mono<ClientResponse> response(final int status, final String body) {
		return Mono.just(
			ClientResponse.create(HttpStatusCode.valueOf(status))
				.header("Content-Type", "application/json")
				.body(body)
				.build()
		);
	}
}
