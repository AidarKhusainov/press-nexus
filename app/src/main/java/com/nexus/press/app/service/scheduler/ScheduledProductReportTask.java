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
		return productReportService.buildDailyReportText(reportDate)
			.doOnNext(report -> log.info("Ежедневный product report:\n{}", report))
			.doOnNext(report -> appMetrics.jobSuccess("product_report_daily", timerSample))
			.doOnError(error -> appMetrics.jobFailure("product_report_daily", timerSample, error))
			.then();
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Product report scheduler stopped");
		}
	}
}
