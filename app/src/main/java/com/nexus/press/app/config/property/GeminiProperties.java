package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gemini")
public record GeminiProperties (
	HttpClientProperties.ClientConfig http,
	String apiKey,
	String model,
	boolean retryOnTooManyRequests,
	int maxRequestsPerMinute,
	int maxRequestsPerDay,
	int maxInputChars
) {

	public GeminiProperties {
		if (maxRequestsPerMinute <= 0) {
			maxRequestsPerMinute = 2;
		}
		if (maxRequestsPerDay <= 0) {
			maxRequestsPerDay = 20;
		}
		if (maxInputChars <= 0) {
			maxInputChars = 12_000;
		}
	}
}
