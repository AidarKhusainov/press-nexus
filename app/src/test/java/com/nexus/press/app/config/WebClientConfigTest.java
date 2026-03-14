package com.nexus.press.app.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

	private static HttpClientProperties defaultProperties() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"http://provider",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(
			HttpClientName.GEMINI, cfg,
			HttpClientName.GROQ, cfg
		));
	}

	private static Map<HttpClientName, Boolean> retryPolicies(final boolean geminiRetryOnTooManyRequests) {
		final Map<HttpClientName, Boolean> policies = new EnumMap<>(HttpClientName.class);
		policies.put(HttpClientName.GEMINI, geminiRetryOnTooManyRequests);
		policies.put(HttpClientName.GROQ, true);
		return policies;
	}
}
