package com.nexus.press.app.news.job;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.NewsPipelineDrainResult;
import com.nexus.press.app.news.usecase.DrainNewsPipelineIngestion;
import com.nexus.press.app.news.usecase.DrainNewsPipelineSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsPipelineTask {

	private final DrainNewsPipelineIngestion drainNewsPipelineIngestion;
	private final DrainNewsPipelineSummary drainNewsPipelineSummary;
	private final NewsPipelineProperties newsPipelineProperties;
	private Disposable ingestionSubscription;
	private Disposable summarySubscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		final Duration interval = newsPipelineProperties.getWorkerInterval();
		ingestionSubscription = scheduledIngestionLoop(interval)
			.doOnSubscribe(s -> log.info("Запущен worker news pipeline ingestion"))
			.subscribe(
				null,
				error -> log.error("Worker news pipeline ingestion остановлен из-за необработанной ошибки", error)
			);
		if (!newsPipelineProperties.isSummaryEnabled()) {
			log.info("Worker news pipeline summary отключен (press.news.pipeline.summary-enabled=false)");
			return;
		}
		summarySubscription = scheduledSummaryLoop(interval)
			.doOnSubscribe(s -> log.info("Запущен worker news pipeline summary"))
			.subscribe(
				null,
				error -> log.error("Worker news pipeline summary остановлен из-за необработанной ошибки", error)
			);
	}

	Flux<NewsPipelineDrainResult> scheduledIngestionLoop(final Duration interval) {
		return scheduledDrainLoop(interval, this::runIngestionTick);
	}

	Flux<NewsPipelineDrainResult> scheduledSummaryLoop(final Duration interval) {
		return scheduledDrainLoop(interval, this::runSummaryTick);
	}

	private Flux<NewsPipelineDrainResult> scheduledDrainLoop(
		final Duration interval,
		final LongFunction<Mono<NewsPipelineDrainResult>> tickRunner
	) {
		final AtomicLong tickCounter = new AtomicLong();
		return Mono.defer(() -> tickRunner.apply(tickCounter.getAndIncrement()))
			.repeatWhen(repeatSignals -> repeatSignals.delayElements(interval));
	}

	private Mono<NewsPipelineDrainResult> runIngestionTick(final long tick) {
		return drainNewsPipelineIngestion.execute()
			.onErrorResume(error -> {
				log.warn("Ошибка в worker news pipeline ingestion на тике {}", tick, error);
				return Mono.empty();
			});
	}

	private Mono<NewsPipelineDrainResult> runSummaryTick(final long tick) {
		return drainNewsPipelineSummary.execute()
			.onErrorResume(error -> {
				log.warn("Ошибка в worker news pipeline summary на тике {}", tick, error);
				return Mono.empty();
			});
	}

	@PreDestroy
	public void stop() {
		if (ingestionSubscription != null && !ingestionSubscription.isDisposed()) {
			ingestionSubscription.dispose();
		}
		if (summarySubscription != null && !summarySubscription.isDisposed()) {
			summarySubscription.dispose();
		}
		log.info("News pipeline worker schedulers stopped");
	}
}
