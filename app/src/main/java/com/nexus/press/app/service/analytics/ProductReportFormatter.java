package com.nexus.press.app.service.analytics;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ProductReportFormatter {

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
		.withLocale(Locale.forLanguageTag("ru"));

	public String toText(final ProductDailyReport report) {
		return """
			Product Report (%s)
			Период: %s — %s
			
			Retention:
			- D1: %.1f%% (%d/%d)
			- D7: %.1f%% (%d/%d)
			
			Feedback quality:
			- useful: %d
			- noise: %d
			- anxious: %d
			- useful rate: %.1f%%
			- noise rate: %.1f%%
			
			Engagement:
			- delivered users: %d
			- feedback events: %d
			- feedback users: %d
			- feedback CTR: %.1f%%
			
			Premium:
			- intent events: %d
			- intent users: %d
			- intent from delivered: %.1f%%
			
			Premium by segment:
			%s
			""".formatted(
			DATE_FMT.format(report.reportDate()),
			report.from(),
			report.to(),
			report.d1RetentionPct(), report.d1RetainedUsers(), report.d1CohortSize(),
			report.d7RetentionPct(), report.d7RetainedUsers(), report.d7CohortSize(),
			report.usefulCount(),
			report.noiseCount(),
			report.anxiousCount(),
			report.usefulRatePct(),
			report.noiseRatePct(),
			report.deliveryUsers(),
			report.feedbackEvents(),
			report.feedbackUsers(),
			report.feedbackCtrPct(),
			report.premiumIntentEvents(),
			report.premiumIntentUsers(),
			report.premiumIntentPct(),
			formatPremiumSegments(report.premiumIntentSegments())
		).strip();
	}

	private String formatPremiumSegments(final List<PremiumIntentSegmentReport> premiumIntentSegments) {
		if (premiumIntentSegments == null || premiumIntentSegments.isEmpty()) {
			return "- no segment data yet";
		}
		return premiumIntentSegments.stream()
			.map(segment -> "- %s: %.1f%% (%d/%d users, %d events)".formatted(
				segment.segment(),
				segment.intentPct(),
				segment.intentUsers(),
				segment.deliveredUsers(),
				segment.intentEvents()
			))
			.reduce((left, right) -> left + "\n" + right)
			.orElse("- no segment data yet");
	}
}
