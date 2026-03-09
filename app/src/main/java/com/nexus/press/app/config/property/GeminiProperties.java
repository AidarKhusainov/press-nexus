package com.nexus.press.app.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties (
    String apiKey,
    String model
) {}
