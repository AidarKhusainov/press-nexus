package com.nexus.press.app.service.feedback;

import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

public record TelegramFeedbackCallbackData(
	FeedbackEventType eventType,
	String newsId
) {

	private static final String PREFIX = "fb|";
	private static final int MAX_CALLBACK_LENGTH = 64;

	public static Optional<TelegramFeedbackCallbackData> parse(final String callbackData) {
		if (!StringUtils.hasText(callbackData)) {
			return Optional.empty();
		}
		final String normalized = callbackData.strip();
		if (normalized.length() > MAX_CALLBACK_LENGTH || !normalized.startsWith(PREFIX)) {
			return Optional.empty();
		}

		final String[] parts = normalized.split("\\|", 3);
		if (parts.length < 2) {
			return Optional.empty();
		}

		final FeedbackEventType eventType;
		try {
			eventType = FeedbackEventType.fromToken(parts[1]);
		} catch (final IllegalArgumentException ex) {
			return Optional.empty();
		}

		String newsId = null;
		if (parts.length == 3 && StringUtils.hasText(parts[2])) {
			newsId = sanitizeNewsId(parts[2]);
		}
		return Optional.of(new TelegramFeedbackCallbackData(eventType, newsId));
	}

	public static String build(final String eventType, final String newsId) {
		final String safeEventType = eventType == null ? "" : eventType.strip().toLowerCase(Locale.ROOT);
		final String safeNewsId = sanitizeNewsId(newsId);
		final String withNews = StringUtils.hasText(safeNewsId)
			? PREFIX + safeEventType + "|" + safeNewsId
			: PREFIX + safeEventType;
		if (withNews.length() <= MAX_CALLBACK_LENGTH) {
			return withNews;
		}
		return PREFIX + safeEventType;
	}

	private static String sanitizeNewsId(final String newsId) {
		if (!StringUtils.hasText(newsId)) {
			return null;
		}
		final String trimmed = newsId.strip();
		final String sanitized = trimmed.replace("|", "");
		return StringUtils.hasText(sanitized) ? sanitized : null;
	}
}
