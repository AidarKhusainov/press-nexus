package com.nexus.press.app.telegram.usecase;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.feedback.model.FeedbackEventType;
import com.nexus.press.app.feedback.usecase.RecordTelegramFeedback;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.usecase.FindUserProfileByChatId;
import com.nexus.press.app.profile.usecase.ListSupportedTopics;
import com.nexus.press.app.profile.usecase.RegisterTelegramUser;
import com.nexus.press.app.profile.usecase.UpdateDigestEnabled;
import com.nexus.press.app.profile.usecase.UpdateDigestFrequency;
import com.nexus.press.app.profile.usecase.UpdateUserTopics;
import com.nexus.press.app.telegram.integration.TelegramBotGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleTelegramUpdateTest {

	@Mock
	private RegisterTelegramUser registerTelegramUser;
	@Mock
	private UpdateDigestEnabled updateDigestEnabled;
	@Mock
	private ListSupportedTopics listSupportedTopics;
	@Mock
	private FindUserProfileByChatId findUserProfileByChatId;
	@Mock
	private UpdateDigestFrequency updateDigestFrequency;
	@Mock
	private UpdateUserTopics updateUserTopics;
	@Mock
	private TelegramBotGateway telegramBotGateway;
	@Mock
	private RecordTelegramFeedback recordTelegramFeedback;
	@Mock
	private AppMetrics appMetrics;

	private HandleTelegramUpdate service;

	@BeforeEach
	void setUp() {
		service = new HandleTelegramUpdate(
			registerTelegramUser,
			updateDigestEnabled,
			listSupportedTopics,
			findUserProfileByChatId,
			updateDigestFrequency,
			updateUserTopics,
			telegramBotGateway,
			new TelegramProperties(null, new TelegramProperties.Bot("bot-token"), null, null),
			recordTelegramFeedback,
			appMetrics
		);
	}

	@Test
	void startCommandRegistersUserAndSendsWelcomeMessage() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(updateDigestEnabled.execute("12345", true)).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(listSupportedTopics.execute()).thenReturn(new LinkedHashSet<>(List.of("world", "economy", "technology")));
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), any())).thenReturn(Mono.empty());

		service.execute(updateWithText("/start")).block();

		verify(registerTelegramUser).execute(any());
		verify(updateDigestEnabled).execute("12345", true);
		verify(telegramBotGateway).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("/topics") && text.contains("/frequency") && text.contains("кнопками ниже")),
			argThat(markup -> markup != null && markup.containsKey("inline_keyboard"))
		);
	}

	@Test
	void frequencyCommandUpdatesProfile() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(findUserProfileByChatId.execute("12345")).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(updateDigestFrequency.execute("12345", DigestFrequency.EVERY_2_DAYS))
			.thenReturn(Mono.just(profile(DigestFrequency.EVERY_2_DAYS)));
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.execute(updateWithText("/frequency 2d")).block();

		verify(updateDigestFrequency).execute("12345", DigestFrequency.EVERY_2_DAYS);
		verify(telegramBotGateway).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("раз в 2 дня")),
			isNull()
		);
	}

	@Test
	void onboardingTopicCallbackUpdatesTopicsAndAnswersCallback() {
		when(listSupportedTopics.execute()).thenReturn(new LinkedHashSet<>(List.of("world", "economy", "technology")));
		when(registerTelegramUser.execute(any()))
			.thenReturn(Mono.just(profile(DigestFrequency.DAILY, true, null, OffsetDateTime.now().minusMinutes(2), List.of("world"))));
		when(updateUserTopics.execute(eq("12345"), argThat(topics -> topics.contains("world") && topics.contains("economy"))))
			.thenReturn(Mono.just(profile(DigestFrequency.DAILY, true, null, OffsetDateTime.now().minusMinutes(2), List.of("economy", "world"))));
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("ob|topic|economy")).block();

		verify(updateUserTopics).execute(eq("12345"), argThat(topics -> topics.contains("world") && topics.contains("economy")));
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("Выбрал тему economy")));
	}

	@Test
	void onboardingTopicsDoneCallbackSendsFrequencyKeyboard() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("ob|topics_done")).block();

		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("частота")));
		verify(telegramBotGateway).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("Темы сохранены")),
			argThat(markup -> markup != null && markup.containsKey("inline_keyboard"))
		);
	}

	@Test
	void onboardingFrequencyCallbackCompletesOnboardingAndRecordsMetric() {
		final OffsetDateTime createdAt = OffsetDateTime.now().minusSeconds(45);
		final UserProfile before = profile(DigestFrequency.DAILY, true, null, createdAt, List.of("world"));
		final UserProfile updated = profile(DigestFrequency.EVERY_2_DAYS, true, OffsetDateTime.now(), createdAt, List.of("world"));

		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(before));
		when(findUserProfileByChatId.execute("12345")).thenReturn(Mono.just(before));
		when(updateDigestFrequency.execute("12345", DigestFrequency.EVERY_2_DAYS)).thenReturn(Mono.just(updated));
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("ob|frequency|2d")).block();

		verify(appMetrics).onboardingCompleted(eq("telegram"), any(Duration.class));
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("Onboarding завершен")));
		verify(telegramBotGateway).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Onboarding завершен")), isNull());
	}

	@Test
	void invalidFrequencyShowsUsageAndSkipsProfileUpdate() {
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.execute(updateWithText("/frequency weekly")).block();

		verify(updateDigestFrequency, never()).execute(any(), any());
		verify(telegramBotGateway).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("/frequency daily")),
			isNull()
		);
	}

	@Test
	void feedbackCallbackStoresEventAndAnswersCallbackQuery() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(recordTelegramFeedback.execute(eq("12345"), eq(FeedbackEventType.USEFUL), eq("news-123"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("fb|useful|news-123")).block();

		verify(recordTelegramFeedback).execute(eq("12345"), eq(FeedbackEventType.USEFUL), eq("news-123"), eq("inline_button"), any());
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("полезно")));
	}

	@Test
	void clickCallbackStoresEventSendsSourceAndAnswersCallback() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(recordTelegramFeedback.execute(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("https://example.com/news-123")), isNull()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("fb|click|news-123", """
			1. Заголовок
			Источник: Example
			https://example.com/news-123
			""")).block();

		verify(recordTelegramFeedback).execute(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any());
		verify(telegramBotGateway).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Источник")), isNull());
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("зафиксировал")));
	}

	@Test
	void clickCallbackAnswersBeforePersistingFeedbackAndSendingSource() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(recordTelegramFeedback.execute(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("https://example.com/news-123")), isNull()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("fb|click|news-123", """
			1. Заголовок
			Источник: Example
			https://example.com/news-123
			""")).block();

		final InOrder inOrder = inOrder(telegramBotGateway, recordTelegramFeedback);
		inOrder.verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("зафиксировал")));
		inOrder.verify(recordTelegramFeedback).execute(eq("12345"), eq(FeedbackEventType.CLICK), eq("news-123"), eq("inline_button"), any());
		inOrder.verify(telegramBotGateway).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Источник")), isNull());
	}

	@Test
	void unsubscribeCallbackDisablesDigestStoresEventAndAnswersCallback() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(updateDigestEnabled.execute("12345", false)).thenReturn(Mono.just(profile(DigestFrequency.DAILY, false)));
		when(recordTelegramFeedback.execute(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("inline_button"), any()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Отключил отправку")), isNull()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("fb|unsubscribe")).block();

		verify(updateDigestEnabled).execute("12345", false);
		verify(recordTelegramFeedback).execute(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("inline_button"), any());
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("Отключил")));
	}

	@Test
	void unsubscribeCommandDisablesDigestAndStoresEvent() {
		when(registerTelegramUser.execute(any())).thenReturn(Mono.just(profile(DigestFrequency.DAILY)));
		when(updateDigestEnabled.execute("12345", false)).thenReturn(Mono.just(profile(DigestFrequency.DAILY, false)));
		when(recordTelegramFeedback.execute(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("command"), any()))
			.thenReturn(Mono.empty());
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Отключил отправку")), isNull()))
			.thenReturn(Mono.empty());

		service.execute(updateWithText("/unsubscribe")).block();

		verify(updateDigestEnabled).execute("12345", false);
		verify(recordTelegramFeedback).execute(eq("12345"), eq(FeedbackEventType.UNSUBSCRIBE), isNull(), eq("command"), any());
		verify(telegramBotGateway).sendMessage(eq("bot-token"), eq("12345"), argThat(text -> text.contains("Чтобы снова включить")), isNull());
	}

	@Test
	void premiumCommandExplainsThatBetaIsFree() {
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), any(), isNull())).thenReturn(Mono.empty());

		service.execute(updateWithText("/premium")).block();

		verify(registerTelegramUser, never()).execute(any());
		verify(telegramBotGateway).sendMessage(
			eq("bot-token"),
			eq("12345"),
			argThat(text -> text.contains("бесплатный") || text.contains("бесплатно")),
			isNull()
		);
	}

	@Test
	void premiumCallbackAnswersThatBetaIsFreeAndDoesNotStoreIntent() {
		when(telegramBotGateway.answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), any())).thenReturn(Mono.empty());

		service.execute(callbackUpdate("pi|299|economy")).block();

		verify(registerTelegramUser, never()).execute(any());
		verify(telegramBotGateway).answerCallbackQuery(eq("bot-token"), eq("cb-id-1"), argThat(text -> text.contains("бесплатно")));
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
		return profile(frequency, digestEnabled, OffsetDateTime.now(), OffsetDateTime.now(), List.of("world"));
	}

	private UserProfile profile(
		final DigestFrequency frequency,
		final boolean digestEnabled,
		final OffsetDateTime onboardedAt,
		final OffsetDateTime createdAt,
		final List<String> topics
	) {
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
			onboardedAt,
			null,
			createdAt,
			OffsetDateTime.now(),
			topics
		);
	}
}
