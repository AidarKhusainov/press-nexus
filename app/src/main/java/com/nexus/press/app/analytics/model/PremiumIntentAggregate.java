package com.nexus.press.app.analytics.model;

public record PremiumIntentAggregate(
	String segment,
	int intentEvents,
	int intentUsers
) {
}
