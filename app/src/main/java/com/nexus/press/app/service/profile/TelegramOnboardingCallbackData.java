package com.nexus.press.app.service.profile;

import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

public record TelegramOnboardingCallbackData(
	Action action,
	String value
) {

	private static final String PREFIX = "ob|";
	private static final int MAX_CALLBACK_LENGTH = 64;
	private static final int VALUE_MAX_LENGTH = 24;

	public enum Action {
		TOPIC("topic"),
		TOPICS_DONE("topics_done"),
		FREQUENCY("frequency");

		private final String token;

		Action(final String token) {
			this.token = token;
		}

		public String token() {
			return token;
		}

		public static Optional<Action> fromToken(final String token) {
			if (!StringUtils.hasText(token)) {
				return Optional.empty();
			}
			final String normalized = token.strip().toLowerCase(Locale.ROOT);
			for (final Action action : values()) {
				if (action.token.equals(normalized)) {
					return Optional.of(action);
				}
			}
			return Optional.empty();
		}
	}

	public static Optional<TelegramOnboardingCallbackData> parse(final String callbackData) {
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

		final Optional<Action> action = Action.fromToken(parts[1]);
		if (action.isEmpty()) {
			return Optional.empty();
		}

		final String value = parts.length == 3 ? sanitizeValue(parts[2]) : null;
		if ((action.get() == Action.TOPIC || action.get() == Action.FREQUENCY) && !StringUtils.hasText(value)) {
			return Optional.empty();
		}
		return Optional.of(new TelegramOnboardingCallbackData(action.get(), value));
	}

	public static String buildTopic(final String topic) {
		return build(Action.TOPIC, topic);
	}

	public static String buildTopicsDone() {
		return build(Action.TOPICS_DONE, null);
	}

	public static String buildFrequency(final String frequencyToken) {
		return build(Action.FREQUENCY, frequencyToken);
	}

	private static String build(final Action action, final String value) {
		if (action == null) {
			throw new IllegalArgumentException("Action is required");
		}
		final String sanitizedValue = sanitizeValue(value);
		if ((action == Action.TOPIC || action == Action.FREQUENCY) && !StringUtils.hasText(sanitizedValue)) {
			throw new IllegalArgumentException("Value is required for action: " + action);
		}
		final String candidate = StringUtils.hasText(sanitizedValue)
			? PREFIX + action.token() + "|" + sanitizedValue
			: PREFIX + action.token();
		if (candidate.length() > MAX_CALLBACK_LENGTH) {
			throw new IllegalArgumentException("Callback data is too long");
		}
		return candidate;
	}

	private static String sanitizeValue(final String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		final String normalized = value.strip()
			.toLowerCase(Locale.ROOT)
			.replace("|", "")
			.replaceAll("[^a-z0-9_-]", "_")
			.replaceAll("_+", "_");
		if (!StringUtils.hasText(normalized)) {
			return null;
		}
		return normalized.length() > VALUE_MAX_LENGTH ? normalized.substring(0, VALUE_MAX_LENGTH) : normalized;
	}
}
