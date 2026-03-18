package com.nexus.press.app.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientConfigTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void retryPolicyCanDisableRetriesForTooManyRequestsPerClient() {
		final var config = new WebClientConfig(
			defaultProperties(),
			retryPolicies(false),
			APP_METRICS
		);

		assertFalse(config.isRetryableError(
			HttpClientName.GEMINI,
			WebClientResponseException.create(429, "Too Many Requests", null, null, null)
		));
		assertTrue(config.isRetryableError(
			HttpClientName.GROQ,
			WebClientResponseException.create(429, "Too Many Requests", null, null, null)
		));
	}

	@Test
	void retryPolicyKeepsServerErrorsRetryable() {
		final var config = new WebClientConfig(
			defaultProperties(),
			retryPolicies(false),
			APP_METRICS
		);

		assertTrue(config.isRetryableError(
			HttpClientName.GEMINI,
			WebClientResponseException.create(503, "Service Unavailable", null, null, null)
		));
	}

	@Test
	void retryPolicyTreatsTransportRequestExceptionsAsRetryable() {
		final var config = new WebClientConfig(
			defaultProperties(),
			retryPolicies(false),
			APP_METRICS
		);

		assertTrue(config.isRetryableError(
			HttpClientName.GROQ,
			new WebClientRequestException(
				new SslHandshakeTimeoutException("handshake timed out"),
				HttpMethod.POST,
				URI.create("https://api.telegram.org"),
				HttpHeaders.EMPTY
			)
		));
	}

	@Test
	void resolveHandshakeTimeoutUsesLargestConfiguredTimeout() {
		final var config = new WebClientConfig(
			defaultProperties(),
			retryPolicies(false),
			APP_METRICS
		);

		assertEquals(
			Duration.ofSeconds(45),
			config.resolveHandshakeTimeout(new HttpClientProperties.Timeout(Duration.ofSeconds(10), Duration.ofSeconds(45)))
		);
		assertTrue(config.usesTls("https://api.telegram.org"));
		assertFalse(config.usesTls("http://localhost:11434"));
	}

	@Test
	void summarizeThrowableFallsBackToMeaningfulClassNameWhenMessageIsMissing() {
		final var config = new WebClientConfig(
			defaultProperties(),
			retryPolicies(false),
			APP_METRICS
		);

		final var ex = new WebClientRequestException(
			ReadTimeoutException.INSTANCE,
			HttpMethod.GET,
			URI.create("https://example.com/feed"),
			HttpHeaders.EMPTY
		);

		assertEquals("ReadTimeoutException", config.summarizeThrowable(ex));
		assertEquals("handshake timed out", config.summarizeThrowable(new SslHandshakeTimeoutException("handshake timed out")));
	}

	private static HttpClientProperties defaultProperties() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"http://provider",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(
			HttpClientName.GEMINI, cfg,
			HttpClientName.GROQ, cfg,
			HttpClientName.TELEGRAM, cfg
		));
	}

	private static Map<HttpClientName, Boolean> retryPolicies(final boolean geminiRetryOnTooManyRequests) {
		final Map<HttpClientName, Boolean> policies = new EnumMap<>(HttpClientName.class);
		policies.put(HttpClientName.GEMINI, geminiRetryOnTooManyRequests);
		policies.put(HttpClientName.GROQ, true);
		return policies;
	}
}
