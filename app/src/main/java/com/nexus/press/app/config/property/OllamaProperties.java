package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.ollama")
public record OllamaProperties(
	HttpClientProperties.ClientConfig http
) {
}
