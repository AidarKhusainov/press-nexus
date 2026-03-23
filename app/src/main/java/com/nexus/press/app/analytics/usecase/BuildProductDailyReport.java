package com.nexus.press.app.analytics.usecase;

import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.nexus.press.app.analytics.model.ProductDailyReport;
import com.nexus.press.app.analytics.model.PremiumIntentSegmentReport;
import com.nexus.press.app.analytics.persistence.query.ProductReportQuery;
import com.nexus.press.app.analytics.policy.PremiumSegmentAnalyticsCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BuildProductDailyReport {

	private final ProductReportQuery productReportQuery;
	private final PremiumSegmentAnalyticsCalculator premiumSegmentAnalyticsCalculator;

	public Mono<ProductDailyReport> execute(final LocalDate reportDate) {
		final LocalDate resolvedDate = reportDate == null ? LocalDate.now(ZoneId.systemDefault()).minusDays(1) : reportDate;
		return productReportQuery.fetch(resolvedDate)
			.map(snapshot -> {
				final ProductReportQuery.FeedbackStats feedback = snapshot.feedbackStats();
				final ProductReportQuery.PremiumIntentStats premium = snapshot.premiumIntentStats();
				final ProductReportQuery.RetentionStats d1 = snapshot.d1RetentionStats();
				final ProductReportQuery.RetentionStats d7 = snapshot.d7RetentionStats();
				final int qualityFeedbackBase = feedback.usefulCount() + feedback.noiseCount() + feedback.anxiousCount();
				final List<PremiumIntentSegmentReport> premiumIntentSegments = premiumSegmentAnalyticsCalculator.build(
					snapshot.deliveredUserTopics(),
					snapshot.premiumIntentBySegment()
				);

				return new ProductDailyReport(
					snapshot.reportDate(),
					snapshot.from(),
					snapshot.to(),
					snapshot.deliveredUsers(),
					feedback.totalEvents(),
					feedback.feedbackUsers(),
					feedback.usefulCount(),
					feedback.noiseCount(),
					feedback.anxiousCount(),
					percent(feedback.usefulCount(), qualityFeedbackBase),
					percent(feedback.noiseCount(), qualityFeedbackBase),
					percent(feedback.feedbackUsers(), snapshot.deliveredUsers()),
					premium.totalEvents(),
					premium.intentUsers(),
					percent(premium.intentUsers(), snapshot.deliveredUsers()),
					d1.cohortSize(),
					d1.retainedUsers(),
					percent(d1.retainedUsers(), d1.cohortSize()),
					d7.cohortSize(),
					d7.retainedUsers(),
					percent(d7.retainedUsers(), d7.cohortSize()),
					premiumIntentSegments
				);
			});
	}

	private double percent(final int numerator, final int denominator) {
		if (denominator <= 0) {
			return 0.0;
		}
		return Math.round((numerator * 1000.0 / denominator)) / 10.0;
	}
}
