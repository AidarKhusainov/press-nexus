package com.nexus.press.app.analytics.web;

import reactor.core.publisher.Mono;
import java.time.LocalDate;
import com.nexus.press.app.analytics.format.ProductReportFormatter;
import com.nexus.press.app.analytics.model.ProductDailyReport;
import com.nexus.press.app.analytics.model.PremiumIntentSegmentReport;
import com.nexus.press.app.analytics.usecase.BuildProductDailyReport;
import com.nexus.press.app.web.generated.api.ProductReportApiDelegate;
import com.nexus.press.app.web.generated.model.PremiumIntentSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class ProductReportController implements ProductReportApiDelegate {

	private final BuildProductDailyReport buildProductDailyReport;
	private final ProductReportFormatter productReportFormatter;

	@Override
	public Mono<com.nexus.press.app.web.generated.model.ProductDailyReport> getDailyProductReport(
		final LocalDate date,
		final ServerWebExchange exchange
	) {
		return buildProductDailyReport.execute(date)
			.map(this::toApiReport);
	}

	@Override
	public Mono<String> getDailyProductReportText(final LocalDate date, final ServerWebExchange exchange) {
		return buildProductDailyReport.execute(date)
			.map(productReportFormatter::toText);
	}

	private com.nexus.press.app.web.generated.model.ProductDailyReport toApiReport(final ProductDailyReport source) {
		final var report = new com.nexus.press.app.web.generated.model.ProductDailyReport(
			source.reportDate(),
			source.from(),
			source.to(),
			source.deliveryUsers(),
			source.feedbackEvents(),
			source.feedbackUsers(),
			source.usefulCount(),
			source.noiseCount(),
			source.anxiousCount(),
			source.usefulRatePct(),
			source.noiseRatePct(),
			source.feedbackCtrPct(),
			source.premiumIntentEvents(),
			source.premiumIntentUsers(),
			source.premiumIntentPct(),
			source.d1CohortSize(),
			source.d1RetainedUsers(),
			source.d1RetentionPct(),
			source.d7CohortSize(),
			source.d7RetainedUsers(),
			source.d7RetentionPct()
		);
		report.setPremiumIntentSegments(
			source.premiumIntentSegments().stream()
				.map(this::toApiPremiumIntentSegment)
				.toList()
		);
		return report;
	}

	private PremiumIntentSegment toApiPremiumIntentSegment(final PremiumIntentSegmentReport source) {
		return new PremiumIntentSegment(
			source.segment(),
			source.deliveredUsers(),
			source.intentUsers(),
			source.intentEvents(),
			source.intentPct()
		);
	}
}
