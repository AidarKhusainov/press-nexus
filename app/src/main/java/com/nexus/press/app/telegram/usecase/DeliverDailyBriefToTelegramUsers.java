package com.nexus.press.app.telegram.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.brief.format.DailyBriefFormatter;
import com.nexus.press.app.brief.model.DailyBrief;
import com.nexus.press.app.brief.model.DailyBriefItem;
import com.nexus.press.app.brief.usecase.BuildDailyBrief;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.feedback.format.TelegramFeedbackCallbackData;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.usecase.FindUsersDueForDigest;
import com.nexus.press.app.profile.usecase.MarkDigestDelivered;
import com.nexus.press.app.telegram.integration.TelegramBotGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverDailyBriefToTelegramUsers {

	private final BuildDailyBrief buildDailyBrief;
	private final DailyBriefFormatter dailyBriefFormatter;
	private final TelegramBotGateway telegramBotGateway;
	private final TelegramProperties telegramProperties;
	private final FindUsersDueForDigest findUsersDueForDigest;
	private final MarkDigestDelivered markDigestDelivered;
	private final AppMetrics appMetrics;

	public Mono<Integer> execute() {
		final TelegramProperties.Delivery delivery = telegramProperties.delivery();
		final var timerSample = appMetrics.startJobTimer();
		if (!delivery.enabled()) {
			appMetrics.jobSuccess("daily_brief_delivery", timerSample);
			return Mono.just(0);
		}
		if (!StringUtils.hasText(botToken())) {
			log.warn("Доставка daily brief включена, но platform.telegram.bot.token не задан");
			appMetrics.jobSuccess("daily_brief_delivery", timerSample);
			return Mono.just(0);
		}

		final OffsetDateTime now = OffsetDateTime.now();
		return findUsersDueForDigest.execute(now)
			.collectList()
			.flatMapMany(users -> {
				if (users.isEmpty()) {
					return deliverLegacyChatIds();
				}
				return Flux.fromIterable(users)
					.concatMap(user -> buildBriefForUser(user, now)
						.flatMap(brief -> sendBriefToChat(user.telegramChatId(), brief)
							.then(markDigestDelivered.execute(user.telegramChatId(), now)))
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
		final TelegramProperties.Delivery delivery = telegramProperties.delivery();
		final List<String> chatIds = delivery.chatIds().stream()
			.filter(StringUtils::hasText)
			.map(String::strip)
			.distinct()
			.toList();
		if (chatIds.isEmpty()) {
			log.warn("Пользователи для персональной доставки не найдены и список fallback chatIds пуст");
			return Flux.empty();
		}

		return buildDailyBrief.execute(new BuildDailyBrief.Request(
			delivery.lookback(),
			delivery.maxItems() == null ? 7 : delivery.maxItems(),
			delivery.language(),
			List.of()
		))
			.flatMapMany(brief -> Flux.fromIterable(chatIds)
				.concatMap(chatId -> sendBriefToChat(chatId, brief)
					.thenReturn(1)
					.doOnNext(sent -> appMetrics.deliveryMessageSuccess("telegram"))
					.onErrorResume(ex -> {
						appMetrics.deliveryMessageFailure("telegram");
						log.error("Не удалось отправить daily brief в chatId={}", chatId, ex);
						return Mono.just(0);
					})));
	}

	private Mono<DailyBrief> buildBriefForUser(final UserProfile user, final OffsetDateTime now) {
		final TelegramProperties.Delivery delivery = telegramProperties.delivery();
		final Duration lookback = resolveLookback(user, now);
		final String language = StringUtils.hasText(user.language())
			? user.language().strip()
			: delivery.language();
		final int maxItems = delivery.maxItems() == null ? 7 : delivery.maxItems();

		return buildDailyBrief.execute(new BuildDailyBrief.Request(
			lookback,
			maxItems,
			language,
			user.topics()
		));
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
		final Duration fallbackLookback = telegramProperties.delivery().lookback();
		if (duration == null || duration.isNegative() || duration.isZero()) {
			return fallbackLookback == null ? Duration.ofHours(24) : fallbackLookback;
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
		final String botToken = botToken();
		final Mono<Void> header = telegramBotGateway.sendMessage(
			botToken,
			chatId,
			dailyBriefFormatter.toTelegramHeader(brief)
		);

		if (brief.items().isEmpty()) {
			return header.then(telegramBotGateway.sendMessage(
				botToken,
				chatId,
				"За выбранный период подтвержденных новостей пока нет."
			));
		}

		return header
			.thenMany(Flux.range(0, brief.items().size())
				.concatMap(index -> sendBriefCard(chatId, brief.items().get(index), index + 1)))
			.then(telegramBotGateway.sendMessage(botToken, chatId, dailyBriefFormatter.toTelegramFooter()));
	}

	private Mono<Void> sendBriefCard(final String chatId, final DailyBriefItem item, final int index) {
		final String botToken = botToken();
		return telegramBotGateway.sendMessage(
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
					Map.of("text", "↗ Источник", "callback_data", TelegramFeedbackCallbackData.build("click", item.newsId())),
					Map.of("text", "🚫 Отписаться", "callback_data", TelegramFeedbackCallbackData.build("unsubscribe", null))
				)
			)
		);
	}

	private String botToken() {
		return telegramProperties.bot().token();
	}
}
