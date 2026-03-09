package com.nexus.press.app.service.delivery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.property.TelegramDeliveryProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.brief.DailyBriefFormatter;
import com.nexus.press.app.service.brief.DailyBriefService;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.brief.model.DailyBriefItem;
import com.nexus.press.app.service.feedback.TelegramFeedbackCallbackData;
import com.nexus.press.app.service.profile.DigestFrequency;
import com.nexus.press.app.service.profile.UserProfile;
import com.nexus.press.app.service.profile.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBriefDeliveryService {

	private final DailyBriefService dailyBriefService;
	private final DailyBriefFormatter dailyBriefFormatter;
	private final TelegramDeliveryService telegramDeliveryService;
	private final TelegramDeliveryProperties telegramDeliveryProperties;
	private final UserProfileService userProfileService;
	private final AppMetrics appMetrics;

	public Mono<Integer> deliverNow() {
		final var timerSample = appMetrics.startJobTimer();
		if (!telegramDeliveryProperties.isEnabled()) {
			appMetrics.jobSuccess("daily_brief_delivery", timerSample);
			return Mono.just(0);
		}
		if (!StringUtils.hasText(telegramDeliveryProperties.getBotToken())) {
			log.warn("Доставка daily brief включена, но press.delivery.telegram.bot-token не задан");
			appMetrics.jobSuccess("daily_brief_delivery", timerSample);
			return Mono.just(0);
		}

		final OffsetDateTime now = OffsetDateTime.now();
		return userProfileService.findUsersDueForDigest(now)
			.collectList()
			.flatMapMany(users -> {
				if (users.isEmpty()) {
					return deliverLegacyChatIds();
				}
				return Flux.fromIterable(users)
					.concatMap(user -> buildBriefForUser(user, now)
						.flatMap(brief -> sendBriefToChat(user.telegramChatId(), brief)
							.then(userProfileService.markDigestDelivered(user.telegramChatId(), now)))
						.thenReturn(1)
						.doOnNext(sent -> appMetrics.deliveryMessageSuccess("telegram"))
						.onErrorResume(ex -> {
							appMetrics.deliveryMessageFailure("telegram");
							log.error("Не удалось отправить персональный daily brief в chatId={}", user.telegramChatId(), ex);
							return Mono.just(0);
						}));
			})
			.reduce(0, Integer::sum)
			.doOnNext(sent -> {
				appMetrics.deliveryBatchSent("telegram", sent);
				appMetrics.jobSuccess("daily_brief_delivery", timerSample);
			})
			.doOnError(ex -> appMetrics.jobFailure("daily_brief_delivery", timerSample, ex));
	}

	private Flux<Integer> deliverLegacyChatIds() {
		final List<String> chatIds = telegramDeliveryProperties.getChatIds().stream()
			.filter(StringUtils::hasText)
			.map(String::strip)
			.distinct()
			.toList();
		if (chatIds.isEmpty()) {
			log.warn("Пользователи для персональной доставки не найдены и список fallback chatIds пуст");
			return Flux.empty();
		}

		return dailyBriefService
			.buildBrief(
				telegramDeliveryProperties.getLookback(),
				telegramDeliveryProperties.getMaxItems() == null ? 7 : telegramDeliveryProperties.getMaxItems(),
				telegramDeliveryProperties.getLanguage())
			.flatMapMany(brief -> Flux.fromIterable(chatIds)
				.concatMap(chatId -> sendBriefToChat(chatId, brief)
					.thenReturn(1)
					.doOnNext(sent -> appMetrics.deliveryMessageSuccess("telegram"))
					.onErrorResume(ex -> {
						appMetrics.deliveryMessageFailure("telegram");
						log.error("Не удалось отправить daily brief в chatId={}", chatId, ex);
						return Mono.just(0);
					})
				));
	}

	private Mono<DailyBrief> buildBriefForUser(final UserProfile user, final OffsetDateTime now) {
		final Duration lookback = resolveLookback(user, now);
		final String language = StringUtils.hasText(user.language())
			? user.language().strip()
			: telegramDeliveryProperties.getLanguage();
		final int maxItems = telegramDeliveryProperties.getMaxItems() == null ? 7 : telegramDeliveryProperties.getMaxItems();

		return dailyBriefService.buildBrief(lookback, maxItems, language, user.topics());
	}

	private Duration resolveLookback(final UserProfile user, final OffsetDateTime now) {
		if (user.lastDeliveryAt() != null && user.lastDeliveryAt().isBefore(now)) {
			final Duration sinceLastDelivery = Duration.between(user.lastDeliveryAt(), now);
			return clampLookback(sinceLastDelivery);
		}
		final DigestFrequency frequency = user.digestFrequency() == null ? DigestFrequency.DAILY : user.digestFrequency();
		return clampLookback(frequency.interval());
	}

	private Duration clampLookback(final Duration duration) {
		final Duration min = Duration.ofHours(6);
		final Duration max = Duration.ofDays(7);
		if (duration == null || duration.isNegative() || duration.isZero()) {
			return telegramDeliveryProperties.getLookback() == null ? Duration.ofHours(24) : telegramDeliveryProperties.getLookback();
		}
		if (duration.compareTo(min) < 0) {
			return min;
		}
		if (duration.compareTo(max) > 0) {
			return max;
		}
		return duration;
	}

	private Mono<Void> sendBriefToChat(final String chatId, final DailyBrief brief) {
		final String botToken = telegramDeliveryProperties.getBotToken();
		final Mono<Void> header = telegramDeliveryService.sendMessage(
			botToken,
			chatId,
			dailyBriefFormatter.toTelegramHeader(brief)
		);

		if (brief.items().isEmpty()) {
			return header.then(telegramDeliveryService.sendMessage(
				botToken,
				chatId,
				"За выбранный период подтвержденных новостей пока нет."
			));
		}

		return header
			.thenMany(Flux.range(0, brief.items().size())
				.concatMap(index -> sendBriefCard(chatId, brief.items().get(index), index + 1)))
			.then(telegramDeliveryService.sendMessage(botToken, chatId, dailyBriefFormatter.toTelegramFooter()));
	}

	private Mono<Void> sendBriefCard(final String chatId, final DailyBriefItem item, final int index) {
		final String botToken = telegramDeliveryProperties.getBotToken();
		return telegramDeliveryService.sendMessage(
			botToken,
			chatId,
			dailyBriefFormatter.toTelegramCard(item, index),
			feedbackKeyboard(item)
		);
	}

	private Map<String, Object> feedbackKeyboard(final DailyBriefItem item) {
		return Map.of(
			"inline_keyboard", List.of(
				List.of(
					Map.of("text", "✅ Полезно", "callback_data", TelegramFeedbackCallbackData.build("useful", item.newsId())),
					Map.of("text", "🔕 Шум", "callback_data", TelegramFeedbackCallbackData.build("noise", item.newsId())),
					Map.of("text", "😟 Тревожно", "callback_data", TelegramFeedbackCallbackData.build("anxious", item.newsId()))
				),
				List.of(
					Map.of("text", "↗ Источник", "url", item.url())
				)
			)
		);
	}
}
