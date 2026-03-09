package com.nexus.press.app.service.analytics;

public record PremiumIntentSegmentReport(
	String segment,
	int deliveredUsers,
	int intentUsers,
	int intentEvents,
	double intentPct
) {
}
