package com.nexus.press.app.service.profile;

import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.config.property.TelegramDeliveryProperties;
import com.nexus.press.app.service.delivery.TelegramDeliveryService;
import com.nexus.press.app.service.feedback.FeedbackEventService;
import com.nexus.press.app.service.feedback.FeedbackEventType;
import com.nexus.press.app.service.feedback.TelegramFeedbackCallbackData;
import com.nexus.press.app.service.premium.PremiumIntentCallbackData;
import com.nexus.press.app.service.premium.PremiumIntentEventService;
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
	private final TelegramDeliveryProperties telegramDeliveryProperties;
	private final FeedbackEventService feedbackEventService;
	private final PremiumIntentEventService premiumIntentEventService;

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

		final var premiumIntent = PremiumIntentCallbackData.parse(callback.data());
		if (premiumIntent.isPresent()) {
			if (!telegramDeliveryProperties.isPremiumEnabled()) {
				return answerCallback(callback.callbackId(), premiumDisabledCallbackText());
			}
			return handlePremiumIntentCallback(callback, premiumIntent.get());
		}

		final var feedback = TelegramFeedbackCallbackData.parse(callback.data());
		if (feedback.isPresent()) {
			return handleFeedbackCallback(callback, feedback.get());
		}
		return answerCallback(callback.callbackId(), "Не удалось распознать действие.");
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

	private Mono<Void> handlePremiumIntentCallback(
		final CallbackContext callback,
		final PremiumIntentCallbackData premiumIntent
	) {
		final var userContext = new TelegramUserContext(
			callback.chatId(),
			callback.userId(),
			callback.username(),
			callback.firstName(),
			callback.language()
		);

		return userProfileService.registerTelegramUser(userContext)
			.flatMap(profile -> {
				final String segment = StringUtils.hasText(premiumIntent.segment())
					? premiumIntent.segment()
					: resolvePremiumSegment(profile.topics());
				return premiumIntentEventService.recordTelegramIntent(
					callback.chatId(),
					premiumIntent.priceRub(),
					segment,
					"inline_button",
					buildPremiumPayload(callback, premiumIntent, profile, segment)
				);
			})
			.then(answerCallback(callback.callbackId(), premiumAckText(premiumIntent.priceRub())))
			.onErrorResume(ex -> {
				log.warn("Не удалось обработать premium callback data={}", callback.data(), ex);
				return answerCallback(callback.callbackId(), "Не сохранил Premium intent, попробуй еще раз.");
			});
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
			.then(sendMessage(message.chatId(), buildWelcomeMessage(message.firstName())));
	}

	private Mono<Void> handleTopics(final MessageContext message, final String text) {
		final String rawTopics = commandArgument(text);
		if (!StringUtils.hasText(rawTopics)) {
			return sendMessage(message.chatId(), buildTopicsUsageMessage());
		}

		final List<String> topics = Arrays.stream(rawTopics.split("[,\\s]+"))
			.map(String::strip)
			.filter(StringUtils::hasText)
			.toList();

		return ensureUserExists(message)
			.then(userProfileService.updateTopics(message.chatId(), topics))
			.flatMap(profile -> sendMessage(message.chatId(),
				"Темы сохранены: " + String.join(", ", profile.topics()) + "\nТеперь выбери частоту: /frequency daily|2d|3d"))
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
			.then(userProfileService.updateFrequency(message.chatId(), frequency))
			.flatMap(profile -> sendMessage(message.chatId(), "Частота обновлена: " + profile.digestFrequency().getDisplayLabel()))
			.onErrorResume(IllegalArgumentException.class, ex -> sendMessage(message.chatId(), ex.getMessage()));
	}

	private Mono<Void> handleProfile(final String chatId) {
		return userProfileService.findByChatId(chatId)
			.flatMap(profile -> sendMessage(chatId, buildProfileMessage(profile)))
			.switchIfEmpty(sendMessage(chatId, "Профиль не найден. Нажми /start для начала onboarding."))
			.onErrorResume(IllegalArgumentException.class, ex -> sendMessage(chatId, "Профиль не найден. Нажми /start для начала onboarding."));
	}

	private Mono<Void> handlePremiumCommand(final MessageContext message) {
		if (!telegramDeliveryProperties.isPremiumEnabled()) {
			return sendMessage(message.chatId(), premiumDisabledMessageText());
		}

		final var userContext = new TelegramUserContext(
			message.chatId(),
			message.userId(),
			message.username(),
			message.firstName(),
			message.language());

		return userProfileService.registerTelegramUser(userContext)
			.flatMap(profile -> {
				final String segment = resolvePremiumSegment(profile.topics());
				return sendMessage(
					message.chatId(),
					buildPremiumOfferMessage(profile, segment),
					premiumOfferKeyboard(segment)
				);
				});
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
			/premium - статус beta (сейчас всё бесплатно)
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
		if (!StringUtils.hasText(telegramDeliveryProperties.getBotToken())) {
			log.warn("Получен onboarding update, но bot token не задан");
			return Mono.empty();
		}
		return telegramDeliveryService.sendMessage(
			telegramDeliveryProperties.getBotToken(),
			chatId,
			text,
			replyMarkup
		);
	}

	private Mono<Void> answerCallback(final String callbackId, final String text) {
		if (!StringUtils.hasText(telegramDeliveryProperties.getBotToken())) {
			log.warn("Получен callback update, но bot token не задан");
			return Mono.empty();
		}
		return telegramDeliveryService.answerCallbackQuery(
			telegramDeliveryProperties.getBotToken(),
			callbackId,
			text
		);
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

	private Map<String, Object> buildPremiumPayload(
		final CallbackContext callback,
		final PremiumIntentCallbackData premiumIntent,
		final UserProfile profile,
		final String segment
	) {
		final var payload = new LinkedHashMap<String, Object>();
		payload.put("telegram_callback_id", callback.callbackId());
		payload.put("telegram_message_id", callback.messageId());
		payload.put("telegram_user_id", callback.userId());
		payload.put("username", callback.username());
		payload.put("callback_data", callback.data());
		payload.put("price_rub", premiumIntent.priceRub());
		payload.put("segment", segment);
		payload.put("topics", profile.topics());
		return payload;
	}

	private Map<String, Object> premiumOfferKeyboard(final String segment) {
		return Map.of(
			"inline_keyboard", List.of(
				List.of(
					Map.of("text", "199 ₽/мес", "callback_data", PremiumIntentCallbackData.build(199, segment)),
					Map.of("text", "299 ₽/мес", "callback_data", PremiumIntentCallbackData.build(299, segment)),
					Map.of("text", "399 ₽/мес", "callback_data", PremiumIntentCallbackData.build(399, segment))
				)
			)
		);
	}

	private String premiumAckText(final int priceRub) {
		return "Зафиксировал интерес к Premium за " + priceRub + " ₽/мес. Спасибо!";
	}

	private String premiumDisabledMessageText() {
		return """
			Сейчас весь продукт бесплатный на этапе обкатки.
			Платную модель включим позже, когда стабилизируем качество под реальных пользователей.
			""";
	}

	private String premiumDisabledCallbackText() {
		return "Сейчас всё бесплатно в beta-режиме.";
	}

	private String resolvePremiumSegment(final List<String> topics) {
		if (topics == null || topics.isEmpty()) {
			return "general";
		}
		final Set<String> topicSet = Set.copyOf(topics);
		int economy = 0;
		int tech = 0;
		int news = 0;
		int lifestyle = 0;

		for (final String topic : topicSet) {
			if ("economy".equals(topic) || "business".equals(topic)) {
				economy++;
				continue;
			}
			if ("technology".equals(topic) || "science".equals(topic)) {
				tech++;
				continue;
			}
			if ("world".equals(topic) || "russia".equals(topic) || "politics".equals(topic) || "society".equals(topic)) {
				news++;
				continue;
			}
			if ("sports".equals(topic) || "culture".equals(topic)) {
				lifestyle++;
			}
		}

		final int max = Math.max(Math.max(economy, tech), Math.max(news, lifestyle));
		if (max <= 0) {
			return "general";
		}
		int leaders = 0;
		leaders += economy == max ? 1 : 0;
		leaders += tech == max ? 1 : 0;
		leaders += news == max ? 1 : 0;
		leaders += lifestyle == max ? 1 : 0;
		if (leaders > 1) {
			return "mixed";
		}
		if (economy == max) {
			return "economy";
		}
		if (tech == max) {
			return "tech";
		}
		if (news == max) {
			return "news";
		}
		return "lifestyle";
	}

	private String commandArgument(final String text) {
		final int split = text.indexOf(' ');
		if (split < 0 || split + 1 >= text.length()) {
			return "";
		}
		return text.substring(split + 1).strip();
	}

	private String buildWelcomeMessage(final String firstName) {
		final String name = StringUtils.hasText(firstName) ? firstName.strip() : "друг";
		return """
			Привет, %s!
			Я помогу настроить персональный daily brief.
			
			1) Выбери темы:
			/topics world,economy,technology
			
			Доступные темы:
			%s
			
			2) Выбери частоту:
			/frequency daily|2d|3d
			
			Сейчас весь функционал бесплатный в beta-режиме.
			""".formatted(name, String.join(", ", userProfileService.supportedTopics()));
	}

	private String buildTopicsUsageMessage() {
		return """
			Укажи темы после команды, например:
			/topics world,economy,technology
			
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

	private String buildPremiumOfferMessage(final UserProfile profile, final String segment) {
		final String topics = profile.topics().isEmpty() ? "пока не выбраны" : String.join(", ", profile.topics());
		final String segmentBenefits = switch (segment) {
			case "economy" -> "Сделаем акцент на рынке, деньгах и бизнес-рисках: меньше шума, больше практичного контекста.";
			case "tech" -> "Сфокусируемся на технологиях и науке: что реально влияет на рынок и повседневную жизнь.";
			case "news" -> "Упор на мировую/российскую повестку без драматизации: что важно знать и какие последствия дальше.";
			case "lifestyle" -> "Больше сигналов по спорту и культуре, без лишнего инфошума.";
			case "mixed" -> "Соберем сбалансированный Premium-дайджест по твоему набору тем, сохраняя только действительно важное.";
			default -> "Соберем персональный Premium-дайджест и подстроим глубину разбора под твои темы.";
		};

		return """
			Ранний Premium (тест)
			Твои текущие темы: %s
			
			%s
			
			Что входит:
			- полный daily brief с дополнительным контекстом;
			- персонализация по темам;
			- weekly deep-dive и архив лучших разборов.
			
			Если тебе ок такой формат, выбери цену, которую готов(а) платить в месяц:
			""".formatted(topics, segmentBenefits);
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
