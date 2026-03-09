package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import com.nexus.press.app.config.property.ProductReportProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.analytics.ProductDailyReport;
import com.nexus.press.app.service.analytics.ProductReportFormatter;
import com.nexus.press.app.service.analytics.ProductReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledProductReportTask {

	private final ProductReportService productReportService;
	private final ProductReportFormatter productReportFormatter;
	private final ProductReportProperties productReportProperties;
	private final AppMetrics appMetrics;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (!productReportProperties.isEnabled()) {
			log.info("Планировщик product report отключен (press.analytics.product-report.enabled=false)");
			return;
		}

		final Duration interval = productReportProperties.getInterval() == null
			? Duration.ofHours(24)
			: productReportProperties.getInterval();

		subscription = Flux.interval(Duration.ofSeconds(45), interval)
			.concatMap(tick -> runDailyReport()
				.onErrorResume(error -> {
					log.warn("Ошибка в scheduled product report на тике {}", tick, error);
					return Mono.empty();
				}))
			.subscribe(
				null,
				error -> log.error("Планировщик product report остановлен из-за необработанной ошибки", error)
			);
	}

	private Mono<Void> runDailyReport() {
		final var timerSample = appMetrics.startJobTimer();
		final LocalDate reportDate = LocalDate.now(ZoneId.systemDefault()).minusDays(1);
		return productReportService.buildDailyReport(reportDate)
			.doOnNext(this::publishProductReportMetrics)
			.map(productReportFormatter::toText)
			.doOnNext(reportText -> log.info("Ежедневный product report:\n{}", reportText))
			.doOnNext(report -> appMetrics.jobSuccess("product_report_daily", timerSample))
			.doOnError(error -> appMetrics.jobFailure("product_report_daily", timerSample, error))
			.then();
	}

	private void publishProductReportMetrics(final ProductDailyReport report) {
		appMetrics.productReportSnapshot(
			report.d1RetentionPct(),
			report.d7RetentionPct(),
			report.usefulRatePct(),
			report.noiseRatePct(),
			report.feedbackCtrPct(),
			report.premiumIntentPct(),
			report.deliveryUsers(),
			report.feedbackUsers(),
			report.usefulCount(),
			report.noiseCount(),
			report.anxiousCount(),
			report.d1CohortSize(),
			report.d7CohortSize()
		);
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Product report scheduler stopped");
		}
	}
}
