package com.nexus.press.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.nexus.press.app.config.property.InternalApiSecurityProperties;
import com.nexus.press.app.config.property.TelegramProperties;

public class RequestAuthenticationFilter implements WebFilter {

	public static final String INTERNAL_API_KEY_HEADER = "X-PressNexus-Api-Key";
	public static final String TELEGRAM_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final Logger log = LoggerFactory.getLogger(RequestAuthenticationFilter.class);

	private static final String DAILY_BRIEF_SEND_PATH = "/api/brief/daily/send";
	private static final String FEEDBACK_PATH = "/api/feedback";
	private static final String PRODUCT_REPORT_PREFIX = "/api/analytics/";
	private static final String NEWS_INTERNAL_PREFIX = "/api/news/";
	private static final String CLUSTERS_PATH = "/api/clusters";
	private static final String TELEGRAM_WEBHOOK_PATH = "/api/telegram/webhook";
	private static final String ACTUATOR_INFO_PATH = "/actuator/info";

	private final InternalApiSecurityProperties internalApiSecurityProperties;
	private final TelegramProperties telegramProperties;

	public RequestAuthenticationFilter(
		final InternalApiSecurityProperties internalApiSecurityProperties,
		final TelegramProperties telegramProperties
	) {
		this.internalApiSecurityProperties = internalApiSecurityProperties;
		this.telegramProperties = telegramProperties;
		if (internalApiSecurityProperties.isEnabled() && !StringUtils.hasText(internalApiSecurityProperties.getApiKey())) {
			throw new IllegalStateException("press.security.internal-api.api-key must be configured when internal API auth is enabled");
		}
	}

	@Override
	public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
		final String path = exchange.getRequest().getPath().pathWithinApplication().value();
		if (requiresTelegramSecret(path)) {
			return validateTelegramSecret(exchange, chain);
		}
		if (requiresInternalApiKey(path)) {
			return validateInternalApiKey(exchange, chain);
		}
		return chain.filter(exchange);
	}

	private Mono<Void> validateTelegramSecret(final ServerWebExchange exchange, final WebFilterChain chain) {
		final String configuredSecret = telegramProperties.webhook().secretToken();
		if (!StringUtils.hasText(configuredSecret)) {
			return chain.filter(exchange);
		}

		final String providedSecret = exchange.getRequest().getHeaders().getFirst(TELEGRAM_SECRET_HEADER);
		if (secureEquals(configuredSecret, providedSecret)) {
			return chain.filter(exchange);
		}

		log.warn("Rejected Telegram webhook request due to missing or invalid secret header");
		return unauthorized(exchange, "invalid_webhook_secret");
	}

	private Mono<Void> validateInternalApiKey(final ServerWebExchange exchange, final WebFilterChain chain) {
		if (!internalApiSecurityProperties.isEnabled()) {
			return chain.filter(exchange);
		}

		final String providedApiKey = exchange.getRequest().getHeaders().getFirst(INTERNAL_API_KEY_HEADER);
		if (secureEquals(internalApiSecurityProperties.getApiKey(), providedApiKey)) {
			return chain.filter(exchange);
		}

		log.warn("Rejected internal API request path={} due to missing or invalid API key",
			exchange.getRequest().getPath().pathWithinApplication().value());
		return unauthorized(exchange, "unauthorized");
	}

	private boolean requiresTelegramSecret(final String path) {
		return TELEGRAM_WEBHOOK_PATH.equals(path);
	}

	private boolean requiresInternalApiKey(final String path) {
		return DAILY_BRIEF_SEND_PATH.equals(path)
			|| FEEDBACK_PATH.equals(path)
			|| path.startsWith(PRODUCT_REPORT_PREFIX)
			|| path.startsWith(NEWS_INTERNAL_PREFIX)
			|| CLUSTERS_PATH.equals(path)
			|| ACTUATOR_INFO_PATH.equals(path);
	}

	private boolean secureEquals(final String expected, final String actual) {
		if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
			return false;
		}
		return MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8),
			actual.getBytes(StandardCharsets.UTF_8)
		);
	}

	private Mono<Void> unauthorized(final ServerWebExchange exchange, final String errorCode) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		final String path = exchange.getRequest().getPath().pathWithinApplication().value();
		if (!path.startsWith("/api/")) {
			return exchange.getResponse().setComplete();
		}

		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		final byte[] body = ("{\"ok\":false,\"error\":\"" + errorCode + "\"}").getBytes(StandardCharsets.UTF_8);
		final DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}
}
