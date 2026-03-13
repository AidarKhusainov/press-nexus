package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.mistral")
public record MistralProperties(
	HttpClientProperties.ClientConfig http,
	String apiKey,
	String model
) {
}
