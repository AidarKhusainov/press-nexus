package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.news")
public record NewsPlatformProperties(
	HttpClientProperties.ClientConfig http
) {
}
