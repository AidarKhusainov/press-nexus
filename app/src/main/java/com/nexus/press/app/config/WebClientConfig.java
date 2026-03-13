package com.nexus.press.app.config;

import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
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

	private final Map<HttpClientName, HttpClientProperties.ClientConfig> clientProperties;
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
			appMetrics
		);
	}

	public WebClientConfig(final HttpClientProperties properties, final AppMetrics appMetrics) {
		this(properties.clients(), appMetrics);
	}

	private WebClientConfig(
		final Map<HttpClientName, HttpClientProperties.ClientConfig> clientProperties,
		final AppMetrics appMetrics
	) {
		this.clientProperties = Map.copyOf(clientProperties);
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

		final var httpClient = HttpClient.create()
			.followRedirect(true)
			.responseTimeout(config.timeout().read())
			.doOnConnected(conn -> conn
				.addHandlerLast(new ReadTimeoutHandler(config.timeout().read().toSeconds(), TimeUnit.SECONDS)))
			.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.timeout().connection().toMillis());

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

			log.info("К платформе {} выполняется запрос {}: {} {}", clientName, requestId, request.method(), request.url());

			return next.exchange(request)
				.doOnNext(response -> {
					appMetrics.httpClientResponse(client, method, response.statusCode().value(), timerSample);
					log.info("От платформы {} получен ответ на запрос {}: {}", clientName, requestId, response.statusCode());
				})
				.doOnError(throwable -> {
					appMetrics.httpClientFailure(client, method, throwable, timerSample);
					if (isExpectedExternalCallError(throwable)) {
						log.warn("От платформы {} ошибка при запросе {}: {}", clientName, requestId, throwable.getMessage());
						log.debug("Стек ошибки запроса {} к платформе {}", requestId, clientName, throwable);
						return;
					}

					log.error("От платформы {} ошибка при запросе {}", clientName, requestId, throwable);
				});
		};
	}

	/**
	 * Создает фильтр для повторных попыток с логированием.
	 */
	private ExchangeFilterFunction createRetryFilter(final HttpClientName clientName,
													 final HttpClientProperties.Retry retryConfig) {
		return (request, next) -> {
			final var requestId = getRequestId(request.attributes());

			return next.exchange(request)
				.retryWhen(
					Retry.backoff(retryConfig.maxAttempts(), retryConfig.backoff())
						.jitter(retryConfig.jitter())
						.filter(this::isRetryableError)
						.doBeforeRetry(retrySignal -> {
							appMetrics.httpClientRetry(clientName.name());
							log.warn("К платформе {} будет выполнена повторная попытка ({} из {}) запроса {}",
								clientName, retrySignal.totalRetries() + 1, retryConfig.maxAttempts(), requestId);
						})
						.onRetryExhaustedThrow((spec, retrySignal) -> {
							log.error("К платформе {} не выполнился запрос {} после {} попыток",
								clientName, requestId, retryConfig.maxAttempts(), retrySignal.failure());
							return retrySignal.failure();
						})
				);
		};
	}

	private String getRequestId(final Map<String, Object> attributes) {
		return (String) attributes.getOrDefault(REQUEST_ID_CONTEXT_KEY, "n/a");
	}

	private boolean isRetryableError(final Throwable throwable) {
		if (throwable instanceof final WebClientResponseException e) {
			return e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
		}
		return throwable instanceof IOException
			|| throwable instanceof TimeoutException
			|| throwable.getCause() instanceof ReadTimeoutException;
	}

	private boolean isExpectedExternalCallError(final Throwable throwable) {
		return throwable instanceof WebClientRequestException
			|| throwable instanceof WebClientResponseException;
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
}
