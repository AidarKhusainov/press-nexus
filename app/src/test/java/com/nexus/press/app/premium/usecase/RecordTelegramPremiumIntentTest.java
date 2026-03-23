package com.nexus.press.app.premium.usecase;

import com.nexus.press.app.premium.persistence.repository.PremiumIntentEventRepository;
import reactor.core.publisher.Mono;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordTelegramPremiumIntentTest {

	@Mock
	private PremiumIntentEventRepository premiumIntentEventRepository;

	@Test
	void blankChatIdSkipsPersistence() {
		final var useCase = new RecordTelegramPremiumIntent(premiumIntentEventRepository);

		useCase.execute("  ", 299, "economy", "inline_button", Map.of()).block();

		verifyNoInteractions(premiumIntentEventRepository);
	}

	@Test
	void unsupportedPriceSkipsPersistence() {
		final var useCase = new RecordTelegramPremiumIntent(premiumIntentEventRepository);

		useCase.execute("12345", 250, "economy", "inline_button", Map.of()).block();

		verifyNoInteractions(premiumIntentEventRepository);
	}

	@Test
	void validRequestNormalizesOptionalValuesBeforePersisting() {
		final var useCase = new RecordTelegramPremiumIntent(premiumIntentEventRepository);
		final Map<String, Object> payload = Map.of("source", "telegram");
		when(premiumIntentEventRepository.recordTelegramIntent("12345", 299, "economy", "inline_button", payload))
			.thenReturn(Mono.empty());

		useCase.execute(" 12345 ", 299, " economy ", " inline_button ", payload).block();

		verify(premiumIntentEventRepository).recordTelegramIntent("12345", 299, "economy", "inline_button", payload);
	}
}
