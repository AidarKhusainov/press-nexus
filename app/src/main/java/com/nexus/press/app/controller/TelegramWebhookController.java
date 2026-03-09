package com.nexus.press.app.controller;

import reactor.core.publisher.Mono;
import java.util.Map;
import com.nexus.press.app.service.profile.TelegramOnboardingBotService;
import com.nexus.press.app.web.generated.api.TelegramWebhookApiDelegate;
import com.nexus.press.app.web.generated.model.ApiStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramWebhookController implements TelegramWebhookApiDelegate {

	private final TelegramOnboardingBotService telegramOnboardingBotService;

	@Override
	public Mono<ApiStatusResponse> postTelegramWebhook(
		final Mono<Map<String, Object>> requestBody,
		final ServerWebExchange exchange
	) {
		final Mono<Map<String, Object>> safeBody = requestBody == null ? Mono.empty() : requestBody;
		return safeBody.defaultIfEmpty(Map.of())
			.flatMap(telegramOnboardingBotService::handleUpdate)
			.thenReturn(status(true, null))
			.onErrorResume(ex -> {
				log.error("Failed to process Telegram webhook update", ex);
				return Mono.just(status(false, "processing_failed"));
			});
	}

	private ApiStatusResponse status(final boolean ok, final String error) {
		final var response = new ApiStatusResponse(ok);
		response.setError(error);
		return response;
	}
}
