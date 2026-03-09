package com.nexus.press.app.controller;

import reactor.core.publisher.Mono;
import java.time.LocalDate;
import com.nexus.press.app.service.analytics.ProductReportService;
import com.nexus.press.app.web.generated.api.ProductReportApiDelegate;
import com.nexus.press.app.web.generated.model.ProductDailyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class ProductReportController implements ProductReportApiDelegate {

	private final ProductReportService productReportService;

	@Override
	public Mono<ProductDailyReport> getDailyProductReport(final LocalDate date, final ServerWebExchange exchange) {
		return productReportService.buildDailyReport(date)
			.map(this::toApiReport);
	}

	@Override
	public Mono<String> getDailyProductReportText(final LocalDate date, final ServerWebExchange exchange) {
		return productReportService.buildDailyReportText(date);
	}

	private ProductDailyReport toApiReport(final com.nexus.press.app.service.analytics.ProductDailyReport source) {
		return new ProductDailyReport(
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
	}
}
