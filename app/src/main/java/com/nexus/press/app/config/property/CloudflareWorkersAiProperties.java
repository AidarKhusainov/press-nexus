package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.cloudflare-workers-ai")
public record CloudflareWorkersAiProperties(
	HttpClientProperties.ClientConfig http,
	String accountId,
	String apiToken,
	String model
) {
}
