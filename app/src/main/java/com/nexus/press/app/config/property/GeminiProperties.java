package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gemini")
public record GeminiProperties (
	HttpClientProperties.ClientConfig http,
	String apiKey,
	String model
) {}
