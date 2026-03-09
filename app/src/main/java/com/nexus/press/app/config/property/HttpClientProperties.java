package com.nexus.press.app.config.property;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "http-client")
public record HttpClientProperties(@NotNull Map<HttpClientName, ClientConfig> clients) {

	public record ClientConfig(@NotBlank String baseUrl, @NotNull Timeout timeout, @NotNull Retry retry) {}

	public record Timeout(@NotNull Duration connection, @NotNull Duration read) {}

	public record Retry(@NotNull Integer maxAttempts, @NotNull Duration backoff, @NotNull Double jitter) {}
}