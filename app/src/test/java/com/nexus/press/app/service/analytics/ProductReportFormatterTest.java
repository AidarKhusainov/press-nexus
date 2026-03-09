package com.nexus.press.app.service.analytics;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReportFormatterTest {

	@Test
	void toTextContainsKeyMetrics() {
		final var formatter = new ProductReportFormatter();
		final var report = new ProductDailyReport(
			LocalDate.parse("2026-03-08"),
			OffsetDateTime.parse("2026-03-08T00:00:00Z"),
			OffsetDateTime.parse("2026-03-09T00:00:00Z"),
			120,
			80,
			40,
			55,
			15,
			10,
			68.8,
			18.8,
			33.3,
			12,
			10,
			8.3,
			50,
			20,
			40.0,
			30,
			9,
			30.0
		);

		final String text = formatter.toText(report);

		assertTrue(text.contains("Product Report"));
		assertTrue(text.contains("D1: 40.0% (20/50)"));
		assertTrue(text.contains("D7: 30.0% (9/30)"));
		assertTrue(text.contains("useful rate: 68.8%"));
		assertTrue(text.contains("feedback CTR: 33.3%"));
		assertTrue(text.contains("intent from delivered: 8.3%"));
	}
}
