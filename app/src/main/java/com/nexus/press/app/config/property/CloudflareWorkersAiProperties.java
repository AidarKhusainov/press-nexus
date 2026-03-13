package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudflare.workers-ai")
public record CloudflareWorkersAiProperties(
	String accountId,
	String apiToken,
	String model
) {
}
