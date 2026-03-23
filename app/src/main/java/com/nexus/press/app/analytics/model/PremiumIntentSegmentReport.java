package com.nexus.press.app.analytics.model;

public record PremiumIntentSegmentReport(
	String segment,
	int deliveredUsers,
	int intentUsers,
	int intentEvents,
	double intentPct
) {
}
