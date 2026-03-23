package com.nexus.press.app.config;

import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.Http11SslContextSpec;
import reactor.util.retry.Retry;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.nexus.press.app.config.property.CloudflareWorkersAiProperties;
import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.GroqProperties;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.MistralProperties;
import com.nexus.press.app.config.property.NewsPlatformProperties;
import com.nexus.press.app.config.property.OllamaProperties;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.observability.AppMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Configuration
public class WebClientConfig {

	private static final String REQUEST_ID_CONTEXT_KEY = "requestId";
	private static final String REQUEST_CONTEXT_ATTRIBUTE_PREFIX = "requestContext.";

	private final Map<HttpClientName, HttpClientProperties.ClientConfig> clientProperties;
	private final Map<HttpClientName, Boolean> retryOnTooManyRequestsByClient;
	private final AppMetrics appMetrics;
	private final Map<HttpClientName, WebClient> clientCache = new ConcurrentHashMap<>();

	@Autowired
	public WebClientConfig(
		final OllamaProperties ollamaProperties,
		final NewsPlatformProperties newsPlatformProperties,
		final GeminiProperties geminiProperties,
		final GroqProperties groqProperties,
		final CloudflareWorkersAiProperties cloudflareWorkersAiProperties,
		final MistralProperties mistralProperties,
		final TelegramProperties telegramProperties,
		final AppMetrics appMetrics
	) {
		this(
			buildClientProperties(
				ollamaProperties,
				newsPlatformProperties,
				geminiProperties,
				groqProperties,
				cloudflareWorkersAiProperties,
				mistralProperties,
				telegramProperties
			),
			buildRetryPolicyProperties(geminiProperties),
			appMetrics
		);
	}

	public WebClientConfig(final HttpClientProperties properties, final AppMetrics appMetrics) {
		this(properties.clients(), defaultRetryPolicyProperties(properties.clients()), appMetrics);
	}

	WebClientConfig(
		final HttpClientProperties properties,
		final Map<HttpClientName, Boolean> retryOnTooManyRequestsByClient,
		final AppMetrics appMetrics
	) {
		this(properties.clients(), retryOnTooManyRequestsByClient, appMetrics);
	}

	private WebClientConfig(
		final Map<HttpClientName, HttpClientProperties.ClientConfig> clientProperties,
		final Map<HttpClientName, Boolean> retryOnTooManyRequestsByClient,
		final AppMetrics appMetrics
	) {
		this.clientProperties = Map.copyOf(clientProperties);
		this.retryOnTooManyRequestsByClient = Map.copyOf(retryOnTooManyRequestsByClient);
		this.appMetrics = appMetrics;
	}

	/**
	 * Получить или создать WebClient по имени.
	 */
	public WebClient getWebClient(final HttpClientName clientName) {
		return clientCache.computeIfAbsent(clientName, this::createWebClient);
	}

	/**
	 * Создание настроенного WebClient c таймаутами, ретраями и логами.
	 */
	private WebClient createWebClient(final HttpClientName clientName) {
		final var config = clientProperties.get(clientName);
		if (config == null) {
			throw new IllegalArgumentException("Configuration for WebClient '" + clientName + "' not found");
		}

		var httpClient = HttpClient.create()
			.followRedirect(true)
			.responseTimeout(config.timeout().read())
			.doOnConnected(conn -> conn
				.addHandlerLast(new ReadTimeoutHandler(config.timeout().read().toSeconds(), TimeUnit.SECONDS)))
			.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.timeout().connection().toMillis());
		if (usesTls(config.baseUrl())) {
			httpClient = httpClient.secure(sslSpec -> {
				sslSpec.sslContext(Http11SslContextSpec.forClient())
					.handshakeTimeout(resolveHandshakeTimeout(config.timeout()));
			});
		}

		return WebClient.builder()
			.baseUrl(config.baseUrl())
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.codecs(codec -> codec.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.filter(requestIdAttrFilter())
			.filter(createRetryFilter(clientName, config.retry()))
			.filter(loggingFilter(clientName))
			.build();
	}

	private ExchangeFilterFunction requestIdAttrFilter() {
		return (request, next) -> {
			final var mutated = ClientRequest.from(request)
				.attribute(REQUEST_ID_CONTEXT_KEY, UUID.randomUUID().toString())
				.build();
			return next.exchange(mutated);
		};
	}

	private ExchangeFilterFunction loggingFilter(final HttpClientName clientName) {
		return (request, next) -> {
			final var requestId = getRequestId(request.attributes());
			final var timerSample = appMetrics.startHttpClientTimer();
			final String method = request.method().name();
			final String client = clientName.name();
			final String requestContext = formatRequestContext(request.attributes());

			log.info("К платформе {} выполняется запрос {}: {} {}{}",
				clientName, requestId, request.method(), request.url(), requestContext);

			return next.exchange(request)
				.doOnNext(response -> {
					appMetrics.httpClientResponse(client, method, response.statusCode().value(), timerSample);
					log.info("От платформы {} получен ответ на запрос {}: {}{}",
						clientName, requestId, response.statusCode(), requestContext);
				})
				.doOnError(throwable -> {
					appMetrics.httpClientFailure(client, method, throwable, timerSample);
					if (isExpectedExternalCallError(throwable)) {
						log.warn("От платформы {} ошибка при запросе {}{}: {}",
							clientName, requestId, requestContext, summarizeThrowable(throwable));
						log.debug("Стек ошибки запроса {} к платформе {}", requestId, clientName, throwable);
						return;
					}

					log.error("От платформы {} ошибка при запросе {}{}",
						clientName, requestId, requestContext, throwable);
				});
		};
	}

	/**
	 * Создает фильтр для повторных попыток с логированием.
	 */
	private ExchangeFilterFunction createRetryFilter(
		final HttpClientName clientName,
		final HttpClientProperties.Retry retryConfig
	) {
		return (request, next) -> {
			final var requestId = getRequestId(request.attributes());
			final var requestContext = formatRequestContext(request.attributes());

			return next.exchange(request)
				.retryWhen(
					Retry.backoff(retryConfig.maxAttempts(), retryConfig.backoff())
						.jitter(retryConfig.jitter())
						.filter(throwable -> isRetryableError(clientName, throwable))
						.doBeforeRetry(retrySignal -> {
							appMetrics.httpClientRetry(clientName.name());
							log.warn("К платформе {} будет выполнена повторная попытка ({} из {}) запроса {}{}",
								clientName, retrySignal.totalRetries() + 1, retryConfig.maxAttempts(), requestId, requestContext);
						})
						.onRetryExhaustedThrow((spec, retrySignal) -> {
							log.error("К платформе {} не выполнился запрос {}{} после {} попыток",
								clientName, requestId, requestContext, retryConfig.maxAttempts(), retrySignal.failure());
							return retrySignal.failure();
						})
				);
		};
	}

	public static String requestContextAttribute(final String key) {
		return REQUEST_CONTEXT_ATTRIBUTE_PREFIX + key;
	}

	private String getRequestId(final Map<String, Object> attributes) {
		return (String) attributes.getOrDefault(REQUEST_ID_CONTEXT_KEY, "n/a");
	}

	boolean isRetryableError(final HttpClientName clientName, final Throwable throwable) {
		if (throwable instanceof final WebClientResponseException e) {
			if (e.getStatusCode().value() == 429) {
				return retryOnTooManyRequestsByClient.getOrDefault(clientName, true);
			}
			return e.getStatusCode().is5xxServerError();
		}
		if (throwable instanceof WebClientRequestException) {
			return true;
		}
		return hasRetryableTransportCause(throwable);
	}

	Duration resolveHandshakeTimeout(final HttpClientProperties.Timeout timeout) {
		final Duration connectionTimeout = timeout == null || timeout.connection() == null
			? Duration.ofSeconds(30)
			: timeout.connection();
		final Duration readTimeout = timeout == null || timeout.read() == null
			? connectionTimeout
			: timeout.read();
		return connectionTimeout.compareTo(readTimeout) >= 0 ? connectionTimeout : readTimeout;
	}

	boolean usesTls(final String baseUrl) {
		try {
			return "https".equalsIgnoreCase(URI.create(baseUrl).getScheme());
		} catch (final Exception ex) {
			return false;
		}
	}

	private boolean isExpectedExternalCallError(final Throwable throwable) {
		return throwable instanceof WebClientRequestException
			|| throwable instanceof WebClientResponseException;
	}

	String summarizeThrowable(final Throwable throwable) {
		Throwable current = throwable;
		String fallback = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
		while (current != null) {
			final String message = current.getMessage();
			if (message != null && !message.isBlank()) {
				return message;
			}
			fallback = current.getClass().getSimpleName();
			current = current.getCause();
		}
		return fallback;
	}

	private boolean hasRetryableTransportCause(final Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof IOException
				|| current instanceof TimeoutException
				|| current instanceof ReadTimeoutException
				|| current instanceof SslHandshakeTimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	String formatRequestContext(final Map<String, Object> attributes) {
		final Map<String, String> context = new TreeMap<>();
		for (final var entry : attributes.entrySet()) {
			if (!entry.getKey().startsWith(REQUEST_CONTEXT_ATTRIBUTE_PREFIX) || entry.getValue() == null) {
				continue;
			}
			final String value = String.valueOf(entry.getValue());
			if (value.isBlank()) {
				continue;
			}
			context.put(entry.getKey().substring(REQUEST_CONTEXT_ATTRIBUTE_PREFIX.length()), value);
		}
		if (context.isEmpty()) {
			return "";
		}
		return " [" + context.entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.reduce((left, right) -> left + ", " + right)
			.orElse("") + "]";
	}

	private static Map<HttpClientName, HttpClientProperties.ClientConfig> buildClientProperties(
		final OllamaProperties ollamaProperties,
		final NewsPlatformProperties newsPlatformProperties,
		final GeminiProperties geminiProperties,
		final GroqProperties groqProperties,
		final CloudflareWorkersAiProperties cloudflareWorkersAiProperties,
		final MistralProperties mistralProperties,
		final TelegramProperties telegramProperties
	) {
		final Map<HttpClientName, HttpClientProperties.ClientConfig> properties = new EnumMap<>(HttpClientName.class);
		properties.put(HttpClientName.OLLAMA, ollamaProperties.http());
		properties.put(HttpClientName.NEWS, newsPlatformProperties.http());
		properties.put(HttpClientName.GEMINI, geminiProperties.http());
		properties.put(HttpClientName.GROQ, groqProperties.http());
		properties.put(HttpClientName.CLOUDFLARE_WORKERS_AI, cloudflareWorkersAiProperties.http());
		properties.put(HttpClientName.MISTRAL, mistralProperties.http());
		properties.put(HttpClientName.TELEGRAM, telegramProperties.http());
		return properties;
	}

	private static Map<HttpClientName, Boolean> buildRetryPolicyProperties(final GeminiProperties geminiProperties) {
		final Map<HttpClientName, Boolean> policies = new EnumMap<>(HttpClientName.class);
		for (final HttpClientName clientName : HttpClientName.values()) {
			policies.put(clientName, true);
		}
		policies.put(HttpClientName.GEMINI, geminiProperties.retryOnTooManyRequests());
		return policies;
	}

	private static Map<HttpClientName, Boolean> defaultRetryPolicyProperties(
		final Map<HttpClientName, HttpClientProperties.ClientConfig> clientProperties
	) {
		final Map<HttpClientName, Boolean> policies = new EnumMap<>(HttpClientName.class);
		for (final HttpClientName clientName : clientProperties.keySet()) {
			policies.put(clientName, true);
		}
		return policies;
	}
}
