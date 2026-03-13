package com.nexus.press.app.service.profile;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.delivery.TelegramDeliveryService;
import com.nexus.press.app.service.feedback.FeedbackEventService;
import com.nexus.press.app.service.feedback.FeedbackEventType;
import com.nexus.press.app.service.feedback.TelegramFeedbackCallbackData;
import com.nexus.press.app.service.premium.PremiumIntentCallbackData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramOnboardingBotService {

	private final UserProfileService userProfileService;
	private final TelegramDeliveryService telegramDeliveryService;
	private final TelegramProperties telegramProperties;
	private final FeedbackEventService feedbackEventService;
	private final AppMetrics appMetrics;

	public Mono<Void> handleUpdate(final Map<String, Object> update) {
		final CallbackContext callback = extractCallback(update);
		if (callback != null) {
			return handleCallback(callback);
		}

		final MessageContext message = extractMessage(update);
		if (message == null || !StringUtils.hasText(message.chatId()) || !StringUtils.hasText(message.text())) {
			return Mono.empty();
		}
		final String text = message.text().strip();
		if (text.startsWith("/start")) {
			return handleStart(message);
		}
		if (text.startsWith("/topics")) {
			return handleTopics(message, text);
		}
		if (text.startsWith("/frequency")) {
			return handleFrequency(message, text);
		}
		if (text.startsWith("/profile")) {
			return handleProfile(message.chatId());
		}
		if (text.startsWith("/unsubscribe") || text.startsWith("/stop")) {
			return handleUnsubscribeCommand(message, text);
		}
		if (text.startsWith("/premium")) {
			return handlePremiumCommand(message);
		}
		return sendHelp(message.chatId());
	}

	private Mono<Void> handleCallback(final CallbackContext callback) {
		if (!StringUtils.hasText(callback.callbackId()) || !StringUtils.hasText(callback.chatId())) {
			return Mono.empty();
		}

		final var onboarding = TelegramOnboardingCallbackData.parse(callback.data());
		if (onboarding.isPresent()) {
			return handleOnboardingCallback(callback, onboarding.get());
		}

		final var premiumIntent = PremiumIntentCallbackData.parse(callback.data());
		if (premiumIntent.isPresent()) {
			return answerCallback(callback.callbackId(), premiumFreeBetaCallbackText());
		}

		final var feedback = TelegramFeedbackCallbackData.parse(callback.data());
		if (feedback.isPresent()) {
			return handleFeedbackCallback(callback, feedback.get());
		}
		return answerCallback(callback.callbackId(), "Не удалось распознать действие.");
	}

	private Mono<Void> handleOnboardingCallback(
		final CallbackContext callback,
		final TelegramOnboardingCallbackData onboarding
	) {
		final var userContext = new TelegramUserContext(
			callback.chatId(),
			callback.userId(),
			callback.username(),
			callback.firstName(),
			callback.language()
		);

		return userProfileService.registerTelegramUser(userContext)
			.flatMap(profile -> switch (onboarding.action()) {
				case TOPIC -> handleOnboardingTopicCallback(callback, profile, onboarding.value());
				case TOPICS_DONE -> handleOnboardingTopicsDoneCallback(callback, profile);
				case FREQUENCY -> handleOnboardingFrequencyCallback(callback, onboarding.value());
			})
			.onErrorResume(IllegalArgumentException.class, ex -> answerCallback(callback.callbackId(), ex.getMessage()))
			.onErrorResume(ex -> {
				log.warn("Не удалось обработать onboarding callback data={}", callback.data(), ex);
				return answerCallback(callback.callbackId(), "Не обработал onboarding действие, попробуй еще раз.");
			});
	}

	private Mono<Void> handleOnboardingTopicCallback(
		final CallbackContext callback,
		final UserProfile profile,
		final String topic
	) {
		if (!userProfileService.supportedTopics().contains(topic)) {
			return answerCallback(callback.callbackId(), "Неизвестная тема: " + topic);
		}

		final var selectedTopics = new LinkedHashSet<>(profile.topics());
		final boolean alreadySelected = selectedTopics.contains(topic);
		if (alreadySelected) {
			if (selectedTopics.size() == 1) {
				return answerCallback(callback.callbackId(), "Нужна хотя бы одна тема.");
			}
			selectedTopics.remove(topic);
		} else {
			selectedTopics.add(topic);
		}

		return userProfileService.updateTopics(callback.chatId(), selectedTopics)
			.flatMap(updated -> answerCallback(callback.callbackId(), topicSelectionAck(topic, !alreadySelected, updated.topics().size())));
	}

	private Mono<Void> handleOnboardingTopicsDoneCallback(final CallbackContext callback, final UserProfile profile) {
		if (profile.topics() == null || profile.topics().isEmpty()) {
			return answerCallback(callback.callbackId(), "Сначала выбери хотя бы одну тему.");
		}

		return answerCallback(callback.callbackId(), "Отлично, теперь частота.")
			.then(sendMessage(
				callback.chatId(),
				buildFrequencySelectionMessage(profile.topics()),
				onboardingFrequencyKeyboard()
			));
	}

	private Mono<Void> handleOnboardingFrequencyCallback(final CallbackContext callback, final String frequencyToken) {
		final DigestFrequency frequency = DigestFrequency.fromCommandToken(frequencyToken).orElse(null);
		if (frequency == null) {
			return answerCallback(callback.callbackId(), "Частота не поддерживается. Выбери daily, 2d или 3d.");
		}
		return applyFrequencySelection(callback.chatId(), frequency, callback.callbackId());
	}

	private Mono<Void> handleFeedbackCallback(
		final CallbackContext callback,
		final TelegramFeedbackCallbackData feedback
	) {
		final String sourceUrl = feedback.eventType() == FeedbackEventType.CLICK
			? extractSourceUrlFromMessage(callback.messageText())
			: null;
		final var userContext = new TelegramUserContext(
			callback.chatId(),
			callback.userId(),
			callback.username(),
			callback.firstName(),
			callback.language()
		);

		return userProfileService.registerTelegramUser(userContext)
			.then(applyFeedbackSideEffectsBeforeEvent(callback.chatId(), feedback.eventType()))
			.then(feedbackEventService.recordTelegramFeedback(
				callback.chatId(),
				feedback.eventType(),
				feedback.newsId(),
				"inline_button",
				buildFeedbackPayload(callback, feedback, sourceUrl)
			))
			.then(applyFeedbackSideEffectsAfterEvent(callback.chatId(), feedback.eventType(), sourceUrl))
			.then(answerCallback(callback.callbackId(), feedbackAckText(feedback.eventType().dbValue())))
			.onErrorResume(ex -> {
				log.warn("Не удалось обработать feedback callback data={}", callback.data(), ex);
				return answerCallback(callback.callbackId(), "Не сохранил feedback, попробуй еще раз.");
			});
	}

	private Mono<Void> applyFeedbackSideEffectsBeforeEvent(final String chatId, final FeedbackEventType eventType) {
		if (eventType != FeedbackEventType.UNSUBSCRIBE) {
			return Mono.empty();
		}
		return userProfileService.updateDigestEnabled(chatId, false).then();
	}

	private Mono<Void> applyFeedbackSideEffectsAfterEvent(
		final String chatId,
		final FeedbackEventType eventType,
		final String sourceUrl
	) {
		if (eventType == FeedbackEventType.CLICK) {
			if (StringUtils.hasText(sourceUrl)) {
				return sendMessage(chatId, "Источник:\n" + sourceUrl);
			}
			return sendMessage(chatId, "Не нашел ссылку в карточке. Открой источник из текста сообщения выше.");
		}
		if (eventType == FeedbackEventType.UNSUBSCRIBE) {
			return sendMessage(chatId, unsubscribeConfirmationText());
		}
		return Mono.empty();
	}

	private Mono<Void> handleStart(final MessageContext message) {
		final var userContext = new TelegramUserContext(
			message.chatId(),
			message.userId(),
			message.username(),
			message.firstName(),
			message.language());

		return userProfileService.registerTelegramUser(userContext)
			.then(userProfileService.updateDigestEnabled(message.chatId(), true))
			.then(sendMessage(message.chatId(), buildWelcomeMessage(message.firstName()), onboardingTopicsKeyboard()));
	}

	private Mono<Void> handleTopics(final MessageContext message, final String text) {
		final String rawTopics = commandArgument(text);
		if (!StringUtils.hasText(rawTopics)) {
			return sendMessage(message.chatId(), buildTopicsUsageMessage(), onboardingTopicsKeyboard());
		}

		final List<String> topics = Arrays.stream(rawTopics.split("[,\\s]+"))
			.map(String::strip)
			.filter(StringUtils::hasText)
			.toList();

		return ensureUserExists(message)
			.then(userProfileService.updateTopics(message.chatId(), topics))
			.flatMap(profile -> sendMessage(
				message.chatId(),
				buildFrequencySelectionMessage(profile.topics()),
				onboardingFrequencyKeyboard()
			))
			.onErrorResume(IllegalArgumentException.class, ex -> sendMessage(message.chatId(), ex.getMessage()));
	}

	private Mono<Void> handleFrequency(final MessageContext message, final String text) {
		final String token = commandArgument(text);
		if (!StringUtils.hasText(token)) {
			return sendMessage(message.chatId(), buildFrequencyUsageMessage());
		}

		final DigestFrequency frequency = DigestFrequency.fromCommandToken(token).orElse(null);
		if (frequency == null) {
			return sendMessage(message.chatId(), buildFrequencyUsageMessage());
		}

		return ensureUserExists(message)
			.then(applyFrequencySelection(message.chatId(), frequency, null));
	}

	private Mono<Void> handleProfile(final String chatId) {
		return userProfileService.findByChatId(chatId)
			.flatMap(profile -> sendMessage(chatId, buildProfileMessage(profile)))
			.switchIfEmpty(sendMessage(chatId, "Профиль не найден. Нажми /start для начала onboarding."))
			.onErrorResume(IllegalArgumentException.class, ex -> sendMessage(chatId, "Профиль не найден. Нажми /start для начала onboarding."));
	}

	private Mono<Void> handlePremiumCommand(final MessageContext message) {
		return sendMessage(message.chatId(), premiumFreeBetaMessageText());
	}

	private Mono<Void> handleUnsubscribeCommand(final MessageContext message, final String commandText) {
		return ensureUserExists(message)
			.then(userProfileService.updateDigestEnabled(message.chatId(), false))
			.then(feedbackEventService.recordTelegramFeedback(
				message.chatId(),
				FeedbackEventType.UNSUBSCRIBE,
				null,
				"command",
				buildUnsubscribeCommandPayload(message, commandText)
			))
			.then(sendMessage(message.chatId(), unsubscribeConfirmationText()))
			.onErrorResume(ex -> {
				log.warn("Не удалось обработать команду отписки command={} chatId={}", commandText, message.chatId(), ex);
				return sendMessage(message.chatId(), "Не удалось отключить рассылку, попробуй еще раз.");
			});
	}

	private Mono<Void> ensureUserExists(final MessageContext message) {
		final var userContext = new TelegramUserContext(
			message.chatId(),
			message.userId(),
			message.username(),
			message.firstName(),
			message.language());
		return userProfileService.registerTelegramUser(userContext).then();
	}

	private Mono<Void> sendHelp(final String chatId) {
		return sendMessage(chatId, """
			Команды:
			/start - начать onboarding
			/topics world,economy,technology - выбрать темы
			/frequency daily|2d|3d - выбрать частоту
			/profile - посмотреть текущие настройки
			/unsubscribe или /stop - отключить рассылку
			/premium - весь beta-функционал сейчас доступен бесплатно
			""");
	}

	private Mono<Void> sendMessage(final String chatId, final String text) {
		return sendMessage(chatId, text, null);
	}

	private Mono<Void> sendMessage(
		final String chatId,
		final String text,
		final Map<String, Object> replyMarkup
	) {
		if (!StringUtils.hasText(botToken())) {
			log.warn("Получен onboarding update, но bot token не задан");
			return Mono.empty();
		}
		return telegramDeliveryService.sendMessage(
			botToken(),
			chatId,
			text,
			replyMarkup
		);
	}

	private Mono<Void> answerCallback(final String callbackId, final String text) {
		if (!StringUtils.hasText(botToken())) {
			log.warn("Получен callback update, но bot token не задан");
			return Mono.empty();
		}
		return telegramDeliveryService.answerCallbackQuery(
			botToken(),
			callbackId,
			text
		);
	}

	private String botToken() {
		return telegramProperties.bot().token();
	}

	private Map<String, Object> buildFeedbackPayload(
		final CallbackContext callback,
		final TelegramFeedbackCallbackData feedback,
		final String sourceUrl
	) {
		final var payload = new LinkedHashMap<String, Object>();
		payload.put("telegram_callback_id", callback.callbackId());
		payload.put("telegram_message_id", callback.messageId());
		payload.put("telegram_user_id", callback.userId());
		payload.put("username", callback.username());
		payload.put("callback_data", callback.data());
		payload.put("event_type", feedback.eventType().dbValue());
		if (feedback.newsId() != null) {
			payload.put("news_id", feedback.newsId());
		}
		if (StringUtils.hasText(sourceUrl)) {
			payload.put("source_url", sourceUrl);
		}
		return payload;
	}

	private Map<String, Object> buildUnsubscribeCommandPayload(final MessageContext message, final String commandText) {
		final var payload = new LinkedHashMap<String, Object>();
		payload.put("command", StringUtils.hasText(commandText) ? commandText.strip() : "/unsubscribe");
		payload.put("telegram_user_id", message.userId());
		payload.put("username", message.username());
		payload.put("language", message.language());
		return payload;
	}

	private String feedbackAckText(final String eventType) {
		return switch (eventType) {
			case "useful" -> "Спасибо, отмечено как полезно.";
			case "noise" -> "Принято, учтем это как шум.";
			case "anxious" -> "Понял, сделаем подачу спокойнее.";
			case "click" -> "Переход к источнику зафиксировал.";
			case "unsubscribe" -> "Отключил доставку дайджеста.";
			default -> "Спасибо за feedback.";
		};
	}

	private String unsubscribeConfirmationText() {
		return """
			Отключил отправку дайджеста.
			Чтобы снова включить подписку, отправь /start.
			""";
	}

	private String premiumFreeBetaMessageText() {
		return """
			Premium пока не продаем.
			Сейчас весь функционал доступен бесплатно в beta-режиме, чтобы спокойно докрутить качество на реальном использовании.
			Ничего дополнительно оплачивать или включать не нужно.
			""";
	}

	private String premiumFreeBetaCallbackText() {
		return "Premium пока не продаем: в beta всё уже доступно бесплатно.";
	}

	private String commandArgument(final String text) {
		final int split = text.indexOf(' ');
		if (split < 0 || split + 1 >= text.length()) {
			return "";
		}
		return text.substring(split + 1).strip();
	}

	private Mono<Void> applyFrequencySelection(
		final String chatId,
		final DigestFrequency frequency,
		final String callbackId
	) {
		return userProfileService.findByChatId(chatId)
			.switchIfEmpty(Mono.error(new IllegalArgumentException("Профиль не найден. Нажми /start для начала onboarding.")))
			.flatMap(before -> userProfileService.updateFrequency(chatId, frequency)
				.flatMap(updated -> {
					final Duration completionDuration = resolveOnboardingCompletionDuration(before, updated);
					if (completionDuration != null) {
						appMetrics.onboardingCompleted("telegram", completionDuration);
						log.info(
							"Onboarding завершен chatId={} durationSeconds={} topics={} frequency={}",
							updated.telegramChatId(),
							completionDuration.toSeconds(),
							updated.topics().size(),
							updated.digestFrequency().getDbValue()
						);
					}
					final Mono<Void> callbackAck = StringUtils.hasText(callbackId)
						? answerCallback(callbackId, completionDuration == null ? "Частота сохранена." : "Onboarding завершен.")
						: Mono.empty();
					return callbackAck.then(sendMessage(chatId, buildFrequencySavedMessage(updated, completionDuration)));
				}))
			.onErrorResume(IllegalArgumentException.class, ex -> {
				if (StringUtils.hasText(callbackId)) {
					return answerCallback(callbackId, ex.getMessage());
				}
				return sendMessage(chatId, ex.getMessage());
			});
	}

	private Duration resolveOnboardingCompletionDuration(final UserProfile before, final UserProfile updated) {
		if (before == null || updated == null || before.onboardedAt() != null || updated.onboardedAt() == null || before.createdAt() == null) {
			return null;
		}
		final Duration raw = Duration.between(before.createdAt(), updated.onboardedAt());
		return raw.isNegative() ? Duration.ZERO : raw;
	}

	private String buildFrequencySavedMessage(final UserProfile profile, final Duration completionDuration) {
		if (completionDuration == null) {
			return "Частота обновлена: " + profile.digestFrequency().getDisplayLabel();
		}
		return """
			Частота обновлена: %s
			Onboarding завершен за %s.
			Готово, дальше дайджест будет приходить автоматически.
			""".formatted(profile.digestFrequency().getDisplayLabel(), formatDurationHuman(completionDuration));
	}

	private String formatDurationHuman(final Duration duration) {
		if (duration == null || duration.isNegative()) {
			return "< 1 мин";
		}
		final long totalSeconds = duration.toSeconds();
		if (totalSeconds < 60) {
			return "< 1 мин";
		}
		final long roundedMinutes = Math.max(1, Math.round(totalSeconds / 60.0));
		return "~" + roundedMinutes + " мин";
	}

	private String buildWelcomeMessage(final String firstName) {
		final String name = StringUtils.hasText(firstName) ? firstName.strip() : "друг";
		return """
			Привет, %s!
			Я помогу настроить персональный daily brief.
			
			1) Выбери темы кнопками ниже.
			
			Доступные темы:
			%s
			
			2) Нажми "✅ Темы выбраны", затем выбери частоту.
			Если удобнее, команды тоже работают:
			/topics world,economy,technology
			/frequency daily|2d|3d
			
			Сейчас весь функционал бесплатный в beta-режиме.
			""".formatted(name, String.join(", ", userProfileService.supportedTopics()));
	}

	private String buildTopicsUsageMessage() {
		return """
			Укажи темы после команды, например:
			/topics world,economy,technology
			или выбери их кнопками ниже.
			
			Доступные темы:
			%s
			""".formatted(String.join(", ", userProfileService.supportedTopics()));
	}

	private String buildFrequencyUsageMessage() {
		return """
			Поддерживаемые значения:
			/frequency daily
			/frequency 2d
			/frequency 3d
			""";
	}

	private String buildFrequencySelectionMessage(final List<String> topics) {
		final String topicsText = topics == null || topics.isEmpty() ? "не выбраны" : String.join(", ", topics);
		return """
			Темы сохранены: %s
			Теперь выбери частоту доставки.
			""".formatted(topicsText);
	}

	private String topicSelectionAck(final String topic, final boolean selected, final int selectedCount) {
		final String action = selected ? "Выбрал" : "Убрал";
		return action + " тему " + topic + ". Выбрано: " + selectedCount;
	}

	private Map<String, Object> onboardingTopicsKeyboard() {
		final var rows = new ArrayList<List<Map<String, Object>>>();
		var currentRow = new ArrayList<Map<String, Object>>(2);
		for (final String topic : userProfileService.supportedTopics()) {
			currentRow.add(Map.of(
				"text", topicButtonLabel(topic),
				"callback_data", TelegramOnboardingCallbackData.buildTopic(topic)
			));
			if (currentRow.size() == 2) {
				rows.add(List.copyOf(currentRow));
				currentRow = new ArrayList<>(2);
			}
		}
		if (!currentRow.isEmpty()) {
			rows.add(List.copyOf(currentRow));
		}
		rows.add(List.of(Map.of(
			"text", "✅ Темы выбраны",
			"callback_data", TelegramOnboardingCallbackData.buildTopicsDone()
		)));
		return Map.of("inline_keyboard", rows);
	}

	private Map<String, Object> onboardingFrequencyKeyboard() {
		return Map.of(
			"inline_keyboard", List.of(
				List.of(
					Map.of("text", "Каждый день", "callback_data", TelegramOnboardingCallbackData.buildFrequency("daily")),
					Map.of("text", "Раз в 2 дня", "callback_data", TelegramOnboardingCallbackData.buildFrequency("2d")),
					Map.of("text", "Раз в 3 дня", "callback_data", TelegramOnboardingCallbackData.buildFrequency("3d"))
				)
			)
		);
	}

	private String topicButtonLabel(final String topic) {
		return switch (topic) {
			case "world" -> "🌍 Мир";
			case "russia" -> "🇷🇺 Россия";
			case "economy" -> "💹 Экономика";
			case "business" -> "🏢 Бизнес";
			case "technology" -> "💻 Технологии";
			case "science" -> "🔬 Наука";
			case "politics" -> "🏛 Политика";
			case "society" -> "🧩 Общество";
			case "sports" -> "⚽ Спорт";
			case "culture" -> "🎭 Культура";
			default -> topic;
		};
	}

	private String extractSourceUrlFromMessage(final String messageText) {
		if (!StringUtils.hasText(messageText)) {
			return null;
		}
		final String[] lines = messageText.split("\\R");
		for (int i = lines.length - 1; i >= 0; i--) {
			final String line = lines[i].strip();
			if (line.startsWith("http://") || line.startsWith("https://")) {
				return line;
			}
		}
		return null;
	}

	private String buildProfileMessage(final UserProfile profile) {
		return """
			Текущий профиль:
			- chatId: %s
			- темы: %s
			- частота: %s
			- язык: %s
			- таймзона: %s
			- onboarding: %s
			""".formatted(
			profile.telegramChatId(),
			profile.topics().isEmpty() ? "не выбраны" : String.join(", ", profile.topics()),
			profile.digestFrequency().getDisplayLabel(),
			profile.language(),
			profile.timezone(),
			profile.onboardedAt() == null ? "не завершен" : "завершен"
		);
	}

	@SuppressWarnings("unchecked")
	private MessageContext extractMessage(final Map<String, Object> update) {
		if (update == null || update.isEmpty()) {
			return null;
		}
		final Map<String, Object> message = asMap(update.get("message"));
		if (message == null) {
			return null;
		}
		final Map<String, Object> from = asMap(message.get("from"));
		final Map<String, Object> chat = asMap(message.get("chat"));
		return new MessageContext(
			asString(chat == null ? null : chat.get("id")),
			asLong(from == null ? null : from.get("id")),
			asString(from == null ? null : from.get("username")),
			asString(from == null ? null : from.get("first_name")),
			asString(from == null ? null : from.get("language_code")),
			asString(message.get("text"))
		);
	}

	@SuppressWarnings("unchecked")
	private CallbackContext extractCallback(final Map<String, Object> update) {
		if (update == null || update.isEmpty()) {
			return null;
		}
		final Map<String, Object> callback = asMap(update.get("callback_query"));
		if (callback == null) {
			return null;
		}
		final Map<String, Object> from = asMap(callback.get("from"));
		final Map<String, Object> message = asMap(callback.get("message"));
		final Map<String, Object> chat = message == null ? null : asMap(message.get("chat"));
		final String messageText = asString(message == null ? null : message.get("text"));
		final String callbackMessageText = StringUtils.hasText(messageText)
			? messageText
			: asString(message == null ? null : message.get("caption"));
		return new CallbackContext(
			asString(callback.get("id")),
			asString(callback.get("data")),
			asString(chat == null ? null : chat.get("id")),
			asLong(from == null ? null : from.get("id")),
			asString(from == null ? null : from.get("username")),
			asString(from == null ? null : from.get("first_name")),
			asString(from == null ? null : from.get("language_code")),
			asLong(message == null ? null : message.get("message_id")),
			callbackMessageText
		);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(final Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	private String asString(final Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String str) {
			return str;
		}
		if (value instanceof Number number) {
			return String.valueOf(number.longValue());
		}
		return String.valueOf(value);
	}

	private Long asLong(final Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(value.toString());
		} catch (final NumberFormatException ignored) {
			return null;
		}
	}

	private record MessageContext(
		String chatId,
		Long userId,
		String username,
		String firstName,
		String language,
		String text
	) {
	}

	private record CallbackContext(
		String callbackId,
		String data,
		String chatId,
		Long userId,
		String username,
		String firstName,
		String language,
		Long messageId,
		String messageText
	) {
	}
}
