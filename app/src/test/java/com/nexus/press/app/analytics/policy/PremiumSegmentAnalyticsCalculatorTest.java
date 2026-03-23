package com.nexus.press.app.analytics.policy;

import java.util.List;
import com.nexus.press.app.analytics.model.DeliveredUserTopics;
import com.nexus.press.app.analytics.model.PremiumIntentAggregate;
import com.nexus.press.app.analytics.model.PremiumIntentSegmentReport;
import com.nexus.press.app.premium.policy.PremiumSegmentResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PremiumSegmentAnalyticsCalculatorTest {

	private final PremiumSegmentAnalyticsCalculator calculator =
		new PremiumSegmentAnalyticsCalculator(new PremiumSegmentResolver());

	@Test
	void buildsSegmentConversionFromDeliveredUsersAndIntentStats() {
		final List<DeliveredUserTopics> deliveredUsers = List.of(
			new DeliveredUserTopics(List.of("economy")),
			new DeliveredUserTopics(List.of("technology")),
			new DeliveredUserTopics(List.of("technology", "world")),
			new DeliveredUserTopics(List.of())
		);

		final List<PremiumIntentAggregate> intents = List.of(
			new PremiumIntentAggregate("tech", 3, 2),
			new PremiumIntentAggregate("economy", 1, 1),
			new PremiumIntentAggregate("lifestyle", 2, 1)
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
