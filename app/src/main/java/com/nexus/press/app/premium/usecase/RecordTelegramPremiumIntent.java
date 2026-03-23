package com.nexus.press.app.premium.usecase;

import com.nexus.press.app.premium.format.PremiumIntentCallbackData;
import com.nexus.press.app.premium.persistence.repository.PremiumIntentEventRepository;
import reactor.core.publisher.Mono;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RecordTelegramPremiumIntent {

	private final PremiumIntentEventRepository premiumIntentEventRepository;

	public Mono<Void> execute(
		final String chatId,
		final int priceRub,
		final String segment,
		final String source,
		final Map<String, Object> payload
	) {
		if (!StringUtils.hasText(chatId) || !PremiumIntentCallbackData.isSupportedPrice(priceRub)) {
			return Mono.empty();
		}

		return premiumIntentEventRepository.recordTelegramIntent(
			chatId.strip(),
			priceRub,
			normalizeNullable(segment),
			normalizeNullable(source),
			payload
		);
	}

	private String normalizeNullable(final String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}
}
