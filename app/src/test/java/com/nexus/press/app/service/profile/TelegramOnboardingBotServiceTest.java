package com.nexus.press.app.service.profile;

import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nexus.press.app.config.property.TelegramDeliveryProperties;
import com.nexus.press.app.service.delivery.TelegramDeliveryService;
import com.nexus.press.app.service.feedback.FeedbackEventService;
import com.nexus.press.app.service.feedback.FeedbackEventType;
import com.nexus.press.app.service.premium.PremiumIntentEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramOnboardingBotServiceTest {

	@Mock
	private UserProfileService userProfileService;
	@Mock
	private TelegramDeliveryService telegramDeliveryService;
	@Mock
	private FeedbackEventService feedbackEventService;
	@Mock
	private PremiumIntentEventService premiumIntentEventService;

	private TelegramOnboardingBotService service;
	private TelegramDeliveryProperties props;

	@BeforeEach
	void setUp() {
		props = new TelegramDeliveryProperties();
		props.setBotToken("bot-token");
		service = new TelegramOnboardingBotService(
			userProfileService,
			telegramDeliveryService,
			props,
			feedbackEventService,
			premiumIntentEventService
		);
	}

	@Test
	void startCommandRegistersUserAndSendsWelcomeMessage() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(userProfileService.updateDigestEnabled("12345", true)).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(userProfileService.supportedTopics()).thenReturn(Set.of("world", "economy", "technology"));
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.handleUpdate(updateWithText("/start")).block();

		verify(userProfileService).registerTelegramUser(any());
		verify(userProfileService).updateDigestEnabled("12345", true);
		verify(telegramDeliveryService).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("/topics") && text.contains("/frequency")),
			isNull()
		);
	}

	@Test
	void frequencyCommandUpdatesProfile() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(userProfileService.updateFrequency("12345", DigestFrequency.EVERY_2_DAYS))
			.thenReturn(Mono.just(profile(DigestFrequency.EVERY_2_DAYS)));
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.handleUpdate(updateWithText("/frequency 2d")).block();

		verify(userProfileService).updateFrequency("12345", DigestFrequency.EVERY_2_DAYS);
		verify(telegramDeliveryService).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("раз в 2 дня")),
			isNull()
		);
	}

	@Test
	void invalidFrequencyShowsUsageAndSkipsProfileUpdate() {
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.handleUpdate(updateWithText("/frequency weekly")).block();

		verify(userProfileService, never()).updateFrequency(any(), any());
		verify(telegramDeliveryService).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("/frequency daily")),
			isNull()
		);
	}

	@Test
	void feedbackCallbackStoresEventAndAnswersCallbackQuery() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(feedbackEventService.recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.USEFUL), eq("news-123"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.handleUpdate(callbackUpdate("fb|useful|news-123")).block();

		verify(feedbackEventService).recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.USEFUL), eq("news-123"), eq("inline_button"), any());
		verify(telegramDeliveryService).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("полезно")));
	}

	@Test
	void clickCallbackStoresEventSendsSourceAndAnswersCallback() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(feedbackEventService.recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("https://example.com/news-123")), isNull()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.handleUpdate(callbackUpdate("fb|click|news-123", """
			1. Заголовок
			Источник: Example
			https://example.com/news-123
			""")).block();

		verify(feedbackEventService).recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any());
		verify(telegramDeliveryService).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Источник")), isNull());
		verify(telegramDeliveryService).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("зафиксировал")));
	}

	@Test
	void unsubscribeCallbackDisablesDigestStoresEventAndAnswersCallback() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(userProfileService.updateDigestEnabled("12345", false)).thenReturn(Mono.just(profile(DigestFrequency.DAILY, false)));
		when(feedbackEventService.recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Отключил отправку")), isNull()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.handleUpdate(callbackUpdate("fb|unsubscribe")).block();

		verify(userProfileService).updateDigestEnabled("12345", false);
		verify(feedbackEventService).recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("inline_button"), any());
		verify(telegramDeliveryService).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("Отключил")));
	}

	@Test
	void unsubscribeCommandDisablesDigestAndStoresEvent() {
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(userProfileService.updateDigestEnabled("12345", false)).thenReturn(Mono.just(profile(DigestFrequency.DAILY, false)));
		when(feedbackEventService.recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("command"), any()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Отключил отправку")), isNull()))
			.thenReturn(Mono.empty());

		service.handleUpdate(updateWithText("/unsubscribe")).block();

		verify(userProfileService).updateDigestEnabled("12345", false);
		verify(feedbackEventService).recordTelegramFeedback(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("command"), any());
		verify(telegramDeliveryService).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Чтобы снова включить")), isNull());
	}

	@Test
	void premiumCommandSendsFreeMessageWhenPremiumDisabled() {
		when(telegramDeliveryService.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.handleUpdate(updateWithText("/premium")).block();

		verify(userProfileService, never()).registerTelegramUser(any());
		verify(telegramDeliveryService).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("бесплатный") || text.contains("бесплатно")),
			isNull()
		);
	}

	@Test
	void premiumCallbackDoesNotStoreIntentWhenPremiumDisabled() {
		when(telegramDeliveryService.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.handleUpdate(callbackUpdate("pi|299|economy")).block();

		verify(premiumIntentEventService, never()).recordTelegramIntent(any(), anyInt(), any(), any(), any());
		verify(userProfileService, never()).registerTelegramUser(any());
		verify(telegramDeliveryService).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("бесплатно")));
	}

	@Test
	void premiumCallbackStoresIntentAndAnswersCallbackQueryWhenPremiumEnabled() {
		props.setPremiumEnabled(true);
		when(userProfileService.registerTelegramUser(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(premiumIntentEventService.recordTelegramIntent(eq("12345"), eq(299), eq("economy"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramDeliveryService.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.handleUpdate(callbackUpdate("pi|299|economy")).block();

		verify(premiumIntentEventService).recordTelegramIntent(eq("12345"), eq(299), eq("economy"), eq("inline_button"), any());
		verify(telegramDeliveryService).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("299")));
	}

	private Map<String, Object> updateWithText(final String text) {
		return Map.of(
			"message", Map.of(
				"text", text,
				"chat", Map.of("id", 12345L),
				"from", Map.of(
					"id", 777L,
					"username", "tester",
					"first_name", "Test",
					"language_code", "ru"
				)
			)
		);
	}

	private Map<String, Object> callbackUpdate(final String callbackData) {
		return callbackUpdate(callbackData, null);
	}

	private Map<String, Object> callbackUpdate(final String callbackData, final String messageText) {
		final var message = new LinkedHashMap<String, Object>();
		message.put("message_id", 77);
		message.put("chat", Map.of("id", 12345L));
		if (messageText != null) {
			message.put("text", messageText);
		}

		return Map.of(
			"callback_query", Map.of(
				"id", "cb-id-1",
				"data", callbackData,
				"from", Map.of(
					"id", 777L,
					"username", "tester",
					"first_name", "Test",
					"language_code", "ru"
				),
				"message", message
			)
		);
	}

	private UserProfile profile(final DigestFrequency frequency) {
		return profile(frequency, true);
	}

	private UserProfile profile(final DigestFrequency frequency, final boolean digestEnabled) {
		return new UserProfile(
			UUID.randomUUID(),
			"12345",
			777L,
			"tester",
			"Test",
			"ru",
			"UTC",
			frequency,
			digestEnabled,
			OffsetDateTime.now(),
			null,
			OffsetDateTime.now(),
			OffsetDateTime.now(),
			List.of("world")
		);
	}
}
