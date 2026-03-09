package com.nexus.press.app.service.profile;

import java.util.Arrays;
import java.time.Duration;
import java.util.Optional;

public enum DigestFrequency {

	DAILY("DAILY", "daily", "каждый день"),
	EVERY_2_DAYS("EVERY_2_DAYS", "2d", "раз в 2 дня"),
	EVERY_3_DAYS("EVERY_3_DAYS", "3d", "раз в 3 дня");

	private final String dbValue;
	private final String commandToken;
	private final String displayLabel;

	DigestFrequency(final String dbValue, final String commandToken, final String displayLabel) {
		this.dbValue = dbValue;
		this.commandToken = commandToken;
		this.displayLabel = displayLabel;
	}

	public String getDbValue() {
		return dbValue;
	}

	public String getCommandToken() {
		return commandToken;
	}

	public String getDisplayLabel() {
		return displayLabel;
	}

	public static Optional<DigestFrequency> fromDbValue(final String value) {
		return Arrays.stream(values())
			.filter(frequency -> frequency.dbValue.equalsIgnoreCase(value))
			.findFirst();
	}

	public static Optional<DigestFrequency> fromCommandToken(final String value) {
		return Arrays.stream(values())
			.filter(frequency -> frequency.commandToken.equalsIgnoreCase(value))
			.findFirst();
	}

	public Duration interval() {
		return switch (this) {
			case DAILY -> Duration.ofDays(1);
			case EVERY_2_DAYS -> Duration.ofDays(2);
			case EVERY_3_DAYS -> Duration.ofDays(3);
		};
	}
}
