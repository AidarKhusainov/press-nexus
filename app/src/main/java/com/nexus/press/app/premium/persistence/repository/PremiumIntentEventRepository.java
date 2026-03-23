package com.nexus.press.app.premium.persistence.repository;

import reactor.core.publisher.Mono;
import java.util.Map;

public interface PremiumIntentEventRepository {

	Mono<Void> recordTelegramIntent(
		String chatId,
		int priceRub,
		String segment,
		String source,
		Map<String, Object> payload
	);
}
