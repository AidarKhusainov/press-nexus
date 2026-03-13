package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.groq")
public record GroqProperties(
	HttpClientProperties.ClientConfig http,
	String apiKey,
	String model
) {
}
