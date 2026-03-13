package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.CloudflareWorkersAiProperties;
import com.nexus.press.app.config.property.GroqProperties;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.MistralProperties;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleSummarizationServicesTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void groqUsesOpenAiCompatibleEndpointAndBearerAuth() {
		final AtomicReference<String> path = new AtomicReference<>();
		final AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		final var service = new GroqSummarizationService(
			stubWebClientConfig(request -> {
				path.set(request.url().getPath());
				headers.set(request.headers());
				return jsonResponse("""
					{"choices":[{"message":{"content":"Groq summary"}}]}
					""");
			}),
			new GroqProperties("groq-key", "llama-3.3-70b-versatile")
		);

		final String summary = service.summarize("Source", "en").block();

		assertEquals("/chat/completions", path.get());
		assertEquals("Bearer groq-key", headers.get().getFirst(HttpHeaders.AUTHORIZATION));
		assertEquals("Groq summary", summary);
		assertEquals("GROQ:llama-3.3-70b-versatile", service.modelName());
	}

	@Test
	void cloudflareUsesAccountScopedEndpoint() {
		final AtomicReference<String> path = new AtomicReference<>();
		final var service = new CloudflareWorkersAiSummarizationService(
			stubWebClientConfig(request -> {
				path.set(request.url().getPath());
				return jsonResponse("""
					{"choices":[{"message":{"content":"Cloudflare summary"}}]}
					""");
			}),
			new CloudflareWorkersAiProperties("acc-123", "cf-token", "@cf/meta/llama-3.1-8b-instruct")
		);

		final String summary = service.summarize("Source", "en").block();

		assertEquals("/client/v4/accounts/acc-123/ai/v1/chat/completions", path.get());
		assertEquals("Cloudflare summary", summary);
	}

	@Test
	void mistralParsesChunkedContentResponses() {
		final AtomicReference<String> path = new AtomicReference<>();
		final var service = new MistralSummarizationService(
			stubWebClientConfig(request -> {
				path.set(request.url().getPath());
				return jsonResponse("""
					{
					  "choices": [
					    {
					      "message": {
					        "content": [
					          { "type": "text", "text": "First." },
					          { "type": "text", "text": "Second." }
					        ]
					      }
					    }
					  ]
					}
					""");
			}),
			new MistralProperties("mistral-key", "mistral-small-latest")
		);

		final String summary = service.summarize("Source", "en").block();

		assertEquals("/v1/chat/completions", path.get());
		assertEquals("First. Second.", summary);
		assertEquals("MISTRAL:mistral-small-latest", service.modelName());
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
			"http://provider",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(
			HttpClientName.GROQ, cfg,
			HttpClientName.CLOUDFLARE_WORKERS_AI, cfg,
			HttpClientName.MISTRAL, cfg
		));
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
