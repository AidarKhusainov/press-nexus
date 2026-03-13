package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groq")
public record GroqProperties(
	String apiKey,
	String model
) {
}
