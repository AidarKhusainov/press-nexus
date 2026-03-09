package com.nexus.press.app.service.feedback;

import java.util.Locale;
import java.util.Set;

public enum FeedbackEventType {
	USEFUL("useful"),
	NOISE("noise"),
	ANXIOUS("anxious"),
	CLICK("click"),
	OPEN("open"),
	UNSUBSCRIBE("unsubscribe");

	private static final Set<String> ALLOWED = Set.of(
		"useful",
		"noise",
		"anxious",
		"click",
		"open",
		"unsubscribe"
	);

	private final String dbValue;

	FeedbackEventType(final String dbValue) {
		this.dbValue = dbValue;
	}

	public String dbValue() {
		return dbValue;
	}

	public static FeedbackEventType fromToken(final String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("feedback event type is blank");
		}
		final String normalized = token.strip().toLowerCase(Locale.ROOT);
		if (!ALLOWED.contains(normalized)) {
			throw new IllegalArgumentException("Unsupported feedback event type: " + token);
		}
		return switch (normalized) {
			case "useful" -> USEFUL;
			case "noise" -> NOISE;
			case "anxious" -> ANXIOUS;
			case "click" -> CLICK;
			case "open" -> OPEN;
			case "unsubscribe" -> UNSUBSCRIBE;
			default -> throw new IllegalArgumentException("Unsupported feedback event type: " + token);
		};
	}
}
