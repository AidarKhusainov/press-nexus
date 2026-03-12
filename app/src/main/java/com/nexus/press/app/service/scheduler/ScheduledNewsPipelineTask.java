package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.Duration;
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
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		final Duration interval = newsPipelineProperties.getWorkerInterval();
		subscription = Flux.interval(Duration.ZERO, interval)
			.concatMap(tick -> newsPipelineWorkerService.drainOnce()
				.onErrorResume(error -> {
					log.warn("Ошибка в worker news pipeline на тике {}", tick, error);
					return reactor.core.publisher.Mono.empty();
				}))
			.doOnSubscribe(s -> log.info("Запущен worker news pipeline"))
			.subscribe(
				null,
				error -> log.error("Worker news pipeline остановлен из-за необработанной ошибки", error)
			);
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("News pipeline worker scheduler stopped");
		}
	}
}
