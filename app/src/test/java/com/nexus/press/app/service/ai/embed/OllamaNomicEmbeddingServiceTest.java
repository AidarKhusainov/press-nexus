package com.nexus.press.app.service.ai.embed;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaNomicEmbeddingServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void embedUsesBatchEndpointAndAggregatesChunkVectors() {
		final AtomicInteger requests = new AtomicInteger();
		final var service = new OllamaNomicEmbeddingService(stubWebClientConfig(request -> {
			requests.incrementAndGet();
			assertEquals("/api/embed", request.url().getPath());
			return jsonResponse("""
				{"embeddings":[[2.0,0.0],[0.0,2.0]]}
				""");
		}));

		final var firstSentence = "a".repeat(900) + ".";
		final var secondSentence = "b".repeat(900) + ".";
		final var embedding = service.embed(firstSentence + " " + secondSentence).block();

		assertEquals(1, requests.get());
		assertTrue(Math.abs(embedding[0] - 0.70710677f) < 0.0001f);
		assertTrue(Math.abs(embedding[1] - 0.70710677f) < 0.0001f);
	}

	@Test
	void embedBatchReturnsOneVectorPerInputAndSkipsBlankTexts() {
		final AtomicInteger requests = new AtomicInteger();
		final var service = new OllamaNomicEmbeddingService(stubWebClientConfig(request -> {
			requests.incrementAndGet();
			assertEquals("/api/embed", request.url().getPath());
			return jsonResponse("""
				{"embeddings":[[1.0,0.0],[0.0,1.0]]}
				""");
		}));

		final var vectors = service.embedBatch(List.of("first text.", "", "second text.")).block();

		assertEquals(1, requests.get());
		assertEquals(3, vectors.size());
		assertArrayEquals(new float[] { 1f, 0f }, vectors.get(0));
		assertArrayEquals(new float[0], vectors.get(1));
		assertArrayEquals(new float[] { 0f, 1f }, vectors.get(2));
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
			"http://ollama",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(HttpClientName.OLLAMA, cfg));
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
