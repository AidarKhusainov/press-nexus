package com.nexus.press.app.service.premium;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

public record PremiumIntentCallbackData(
	int priceRub,
	String segment
) {

	private static final String PREFIX = "pi|";
	private static final int MAX_CALLBACK_LENGTH = 64;
	private static final int SEGMENT_MAX_LENGTH = 24;
	private static final Set<Integer> ALLOWED_PRICES = Set.of(199, 299, 399);

	public static Optional<PremiumIntentCallbackData> parse(final String callbackData) {
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

		final int priceRub;
		try {
			priceRub = Integer.parseInt(parts[1]);
		} catch (final NumberFormatException ex) {
			return Optional.empty();
		}
		if (!isSupportedPrice(priceRub)) {
			return Optional.empty();
		}

		String segment = null;
		if (parts.length == 3 && StringUtils.hasText(parts[2])) {
			segment = sanitizeSegment(parts[2]);
		}
		return Optional.of(new PremiumIntentCallbackData(priceRub, segment));
	}

	public static String build(final int priceRub, final String segment) {
		if (!isSupportedPrice(priceRub)) {
			throw new IllegalArgumentException("Unsupported premium price: " + priceRub);
		}

		final String safeSegment = sanitizeSegment(segment);
		final String withSegment = StringUtils.hasText(safeSegment)
			? PREFIX + priceRub + "|" + safeSegment
			: PREFIX + priceRub;
		if (withSegment.length() <= MAX_CALLBACK_LENGTH) {
			return withSegment;
		}
		return PREFIX + priceRub;
	}

	public static boolean isSupportedPrice(final int priceRub) {
		return ALLOWED_PRICES.contains(priceRub);
	}

	private static String sanitizeSegment(final String segment) {
		if (!StringUtils.hasText(segment)) {
			return null;
		}
		final String normalized = segment.strip()
			.toLowerCase(Locale.ROOT)
			.replace("|", "")
			.replaceAll("[^a-z0-9_-]", "_")
			.replaceAll("_+", "_");
		if (!StringUtils.hasText(normalized)) {
			return null;
		}
		return normalized.length() > SEGMENT_MAX_LENGTH ? normalized.substring(0, SEGMENT_MAX_LENGTH) : normalized;
	}
}
