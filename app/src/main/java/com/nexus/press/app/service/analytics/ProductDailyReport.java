package com.nexus.press.app.service.analytics;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ProductDailyReport(
	LocalDate reportDate,
	OffsetDateTime from,
	OffsetDateTime to,
	int deliveryUsers,
	int feedbackEvents,
	int feedbackUsers,
	int usefulCount,
	int noiseCount,
	int anxiousCount,
	double usefulRatePct,
	double noiseRatePct,
	double feedbackCtrPct,
	int premiumIntentEvents,
	int premiumIntentUsers,
	double premiumIntentPct,
	int d1CohortSize,
	int d1RetainedUsers,
	double d1RetentionPct,
	int d7CohortSize,
	int d7RetainedUsers,
	double d7RetentionPct,
	List<PremiumIntentSegmentReport> premiumIntentSegments
) {
}
