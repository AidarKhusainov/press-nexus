package com.nexus.press.app.service.analytics;

import java.util.List;
import com.nexus.press.app.service.premium.PremiumSegmentResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PremiumSegmentAnalyticsCalculatorTest {

	private final PremiumSegmentAnalyticsCalculator calculator =
		new PremiumSegmentAnalyticsCalculator(new PremiumSegmentResolver());

	@Test
	void buildsSegmentConversionFromDeliveredUsersAndIntentStats() {
		final List<PremiumSegmentAnalyticsCalculator.DeliveredUserTopics> deliveredUsers = List.of(
			new PremiumSegmentAnalyticsCalculator.DeliveredUserTopics(List.of("economy")),
			new PremiumSegmentAnalyticsCalculator.DeliveredUserTopics(List.of("technology")),
			new PremiumSegmentAnalyticsCalculator.DeliveredUserTopics(List.of("technology", "world")),
			new PremiumSegmentAnalyticsCalculator.DeliveredUserTopics(List.of())
		);

		final List<PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate> intents = List.of(
			new PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate("tech", 3, 2),
			new PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate("economy", 1, 1),
			new PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate("lifestyle", 2, 1)
		);

		final List<PremiumIntentSegmentReport> report = calculator.build(deliveredUsers, intents);

		assertEquals(5, report.size());
		assertEquals(new PremiumIntentSegmentReport("tech", 1, 2, 3, 200.0), report.get(0));
		assertEquals(new PremiumIntentSegmentReport("economy", 1, 1, 1, 100.0), report.get(1));
		assertEquals(new PremiumIntentSegmentReport("lifestyle", 0, 1, 2, 0.0), report.get(2));
		assertEquals(new PremiumIntentSegmentReport("general", 1, 0, 0, 0.0), report.get(3));
		assertEquals(new PremiumIntentSegmentReport("mixed", 1, 0, 0, 0.0), report.get(4));
	}
}
