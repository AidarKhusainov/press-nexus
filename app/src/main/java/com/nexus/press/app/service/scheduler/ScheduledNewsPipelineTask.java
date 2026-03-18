package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.function.LongFunction;
import java.util.concurrent.atomic.AtomicLong;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.service.news.NewsPipelineWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsPipelineTask {

	private final NewsPipelineWorkerService newsPipelineWorkerService;
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
		summarySubscription = scheduledSummaryLoop(interval)
			.doOnSubscribe(s -> log.info("Запущен worker news pipeline summary"))
			.subscribe(
				null,
				error -> log.error("Worker news pipeline summary остановлен из-за необработанной ошибки", error)
			);
	}

	Flux<NewsPipelineWorkerService.DrainResult> scheduledIngestionLoop(final Duration interval) {
		return scheduledDrainLoop(interval, this::runIngestionTick);
	}

	Flux<NewsPipelineWorkerService.DrainResult> scheduledSummaryLoop(final Duration interval) {
		return scheduledDrainLoop(interval, this::runSummaryTick);
	}

	private Flux<NewsPipelineWorkerService.DrainResult> scheduledDrainLoop(
		final Duration interval,
		final LongFunction<Mono<NewsPipelineWorkerService.DrainResult>> tickRunner
	) {
		final AtomicLong tickCounter = new AtomicLong();
		return Mono.defer(() -> tickRunner.apply(tickCounter.getAndIncrement()))
			.repeatWhen(repeatSignals -> repeatSignals.delayElements(interval));
	}

	private Mono<NewsPipelineWorkerService.DrainResult> runIngestionTick(final long tick) {
		return newsPipelineWorkerService.drainIngestionOnce()
			.onErrorResume(error -> {
				log.warn("Ошибка в worker news pipeline ingestion на тике {}", tick, error);
				return Mono.empty();
			});
	}

	private Mono<NewsPipelineWorkerService.DrainResult> runSummaryTick(final long tick) {
		return newsPipelineWorkerService.drainSummaryOnce()
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
