package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mistral")
public record MistralProperties(
	String apiKey,
	String model
) {
}
