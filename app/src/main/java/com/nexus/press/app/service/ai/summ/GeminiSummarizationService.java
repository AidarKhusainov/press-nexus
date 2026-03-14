package com.nexus.press.app.service.ai.summ;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.HttpClientName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Slf4j
@Component("geminiSummarizationService")
public class GeminiSummarizationService implements ProviderSummarizationService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final int LOG_BODY_LIMIT = 512;
	private static final Duration CIRCUIT_BREAKER_OPEN_DURATION = Duration.ofMinutes(30);
	private static final int CIRCUIT_BREAKER_WINDOW_SIZE = 8;
	private static final int CIRCUIT_BREAKER_MIN_CALLS = 4;

	private final WebClientConfig webClientConfig;
	private final GeminiProperties properties;
	private final AtomicReference<LocalDate> requestDayUtc = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));
	private final AtomicInteger requestsToday = new AtomicInteger();
	private final CircuitBreaker circuitBreaker;
	private final RateLimiter rateLimiter;

	public GeminiSummarizationService(
		final WebClientConfig webClientConfig,
		final GeminiProperties properties
	) {
		this.webClientConfig = webClientConfig;
		this.properties = properties;
		this.circuitBreaker = CircuitBreaker.of("geminiSummarization", circuitBreakerConfig());
		this.rateLimiter = RateLimiter.of("geminiSummarization", rateLimiterConfig(properties));
	}

	@Override
	public SummarizationProvider provider() {
		return SummarizationProvider.GEMINI;
	}

	@Override
	public String modelName() {
		return provider().name() + ":" + properties.model();
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(properties.apiKey()) && StringUtils.hasText(properties.model());
	}

	@Override
	public Mono<String> summarize(final String text, final String lang) {
		if (text == null || text.isBlank()) {
			return Mono.just("");
		}

		final String preparedText = truncateInput(text);
		final Map<String, Object> payload = Map.of(
			"system_instruction", Map.of(
				"parts", List.of(Map.of("text", SummarizationPromptSupport.systemInstruction(lang)))
			),
			"contents", List.of(Map.of(
				"role", "user",
				"parts", List.of(Map.of("text", SummarizationPromptSupport.userPrompt(preparedText, lang)))
			)),
			"generationConfig", Map.of(
				"temperature", 0.2,
				"topP", 0.95,
				"thinkingConfig", Map.of("thinkingBudget", 0)
			)
		);

		return Mono.defer(() -> {
			final long start = System.nanoTime();
			return Mono.defer(() -> {
				if (!reserveDailyBudget()) {
					return Mono.error(new DailyBudgetExhaustedException(
						"Gemini daily budget exhausted for UTC day"
					));
				}

				return webClientConfig.getWebClient(HttpClientName.GEMINI)
				.post()
				.uri("/models/" + properties.model() + ":generateContent")
				.headers(headers -> {
					headers.set("x-goog-api-key", properties.apiKey());
					headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
					headers.set("x-goog-api-client", "press-nexus/0.1.0");
				})
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(payload)
				.exchangeToMono(response -> {
					if (response.statusCode().is2xxSuccessful()) {
						return response.bodyToMono(String.class);
					}
					return response.bodyToMono(String.class)
						.defaultIfEmpty("")
						.flatMap(body -> {
							final int status = response.statusCode().value();
							if (status == 429) {
								return Mono.error(new SummarizationThrottledException(
									"Gemini throttled with 429: " + trimForLog(body)
								));
							}
							if (status == 400) {
								return Mono.error(new IllegalStateException(
									"Gemini bad request: inputChars=%d lang=%s body=%s"
										.formatted(preparedText.length(), lang, trimForLog(body))
								));
							}
							if (status >= 500) {
								return Mono.error(new GeminiTransientException(
									"Gemini server error %d: %s".formatted(status, trimForLog(body))
								));
							}
							return Mono.error(new IllegalStateException(
								"Gemini unexpected status %d: %s".formatted(status, trimForLog(body))
							));
						});
				})
				.map(GeminiSummarizationService::parseSummary)
					.onErrorMap(WebClientRequestException.class, ex -> new GeminiTransientException(
						"Gemini request failed: " + ex.getMessage(), ex
					));
			})
				.doOnSuccess(summary -> {
					final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
					log.info("Gemini суммаризация: вход={} симв, выжимка={} симв, {} мс",
						preparedText.length(), summary.length(), elapsedMs);
				})
				.doOnError(ex -> {
					final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
					log.warn("Gemini суммаризация завершилась ошибкой после {} мс", elapsedMs, ex);
				});
		})
			.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
			.onErrorMap(CallNotPermittedException.class, ex -> new SummarizationThrottledException(
				"Gemini circuit breaker is open", ex
			))
			.transformDeferred(RateLimiterOperator.of(rateLimiter))
			.onErrorMap(RequestNotPermitted.class, ex -> new SummarizationThrottledException(
				"Gemini rate limiter rejected request", ex
			));
	}

	private boolean reserveDailyBudget() {
		synchronized (requestsToday) {
			final LocalDate today = LocalDate.now(ZoneOffset.UTC);
			if (!today.equals(requestDayUtc.get())) {
				requestDayUtc.set(today);
				requestsToday.set(0);
			}
			if (requestsToday.get() >= properties.maxRequestsPerDay()) {
				return false;
			}
			requestsToday.incrementAndGet();
			return true;
		}
	}

	private String truncateInput(final String text) {
		if (text.length() <= properties.maxInputChars()) {
			return text;
		}
		final String truncated = text.substring(0, properties.maxInputChars()).strip();
		log.info("Gemini вход урезан: исходный={} симв, лимит={} симв", text.length(), properties.maxInputChars());
		return truncated;
	}

	private static String trimForLog(final String body) {
		if (body == null || body.isBlank()) {
			return "<empty>";
		}
		final String normalized = body.replaceAll("\\s+", " ").strip();
		if (normalized.length() <= LOG_BODY_LIMIT) {
			return normalized;
		}
		return normalized.substring(0, LOG_BODY_LIMIT) + "...";
	}

	private CircuitBreakerConfig circuitBreakerConfig() {
		return CircuitBreakerConfig.custom()
			.slidingWindowSize(CIRCUIT_BREAKER_WINDOW_SIZE)
			.minimumNumberOfCalls(CIRCUIT_BREAKER_MIN_CALLS)
			.failureRateThreshold(50.0f)
			.waitDurationInOpenState(CIRCUIT_BREAKER_OPEN_DURATION)
			.recordException(this::isCircuitBreakerFailure)
			.build();
	}

	private RateLimiterConfig rateLimiterConfig(final GeminiProperties geminiProperties) {
		return RateLimiterConfig.custom()
			.limitRefreshPeriod(Duration.ofMinutes(1))
			.limitForPeriod(geminiProperties.maxRequestsPerMinute())
			.timeoutDuration(Duration.ZERO)
			.build();
	}

	private boolean isCircuitBreakerFailure(final Throwable throwable) {
		return throwable instanceof SummarizationThrottledException
			&& !(throwable instanceof DailyBudgetExhaustedException)
			|| throwable instanceof GeminiTransientException
			|| throwable instanceof WebClientRequestException;
	}

	private static final class DailyBudgetExhaustedException extends SummarizationThrottledException {

		private DailyBudgetExhaustedException(final String message) {
			super(message);
		}
	}

	static String parseSummary(final String json) {
		try {
			final JsonNode parts = OBJECT_MAPPER.readTree(json)
				.path("candidates")
				.path(0)
				.path("content")
				.path("parts");
			if (!parts.isArray()) {
				return "";
			}

			final StringBuilder summary = new StringBuilder();
			for (final JsonNode part : parts) {
				final String text = part.path("text").asText("");
				if (!text.isBlank()) {
					if (!summary.isEmpty()) {
						summary.append(' ');
					}
					summary.append(text.trim());
				}
			}
			return summary.toString();
		} catch (final Exception ex) {
			log.warn("Gemini ответ не удалось распарсить: {}", json, ex);
			return "";
		}
	}
}
