package com.nexus.press.app.news.job;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.Duration;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.usecase.FetchNews;
import com.nexus.press.app.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsFetchTask {

	private final FetchNews fetchNews;
	private final NewsPipelineProperties newsPipelineProperties;
	private final AppMetrics appMetrics;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		final Duration interval = newsPipelineProperties.getDiscoveryInterval();
		subscription = Flux.concat(
				Flux.defer(() -> runFetchCycle("scheduler_news_fetch_initial")),
				Flux.interval(interval)
					.concatMap(tick -> runFetchCycle("scheduler_news_fetch_tick"))
			)
			.doOnSubscribe(s -> log.info("Запущен планировщик задач для получения новостей"))
			.subscribe(
				null,
				error -> log.error("Планировщик задач получения новостей остановлен из-за необработанной ошибки", error)
			);
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Scheduler stopped");
		}
	}

	Flux<?> runFetchCycle(final String cycleName) {
		final var timerSample = appMetrics.startJobTimer();
		return fetchNews.execute()
			.doOnComplete(() -> appMetrics.jobSuccess(cycleName, timerSample))
			.onErrorResume(error -> {
				appMetrics.jobFailure(cycleName, timerSample, error);
				log.warn("Ошибка в планировщике задач при получении новостей [{}]", cycleName, error);
				return Flux.empty();
			});
	}
}
