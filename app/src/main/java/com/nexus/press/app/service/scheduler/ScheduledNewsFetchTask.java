package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.Duration;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.NewsFetchService;
import com.nexus.press.app.service.news.NewsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsFetchTask {

	private final NewsFetchService newsFetchService;
	private final NewsPersistenceService newsPersistenceService;
	private final NewsPipelineProperties newsPipelineProperties;
	private final AppMetrics appMetrics;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void startScraping() {
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
	public void stopScraping() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Scheduler stopped");
		}
	}

	private Flux<?> runFetchCycle(final String cycleName) {
		final var timerSample = appMetrics.startJobTimer();
		return newsPersistenceService.loadPipelineBacklog()
			.flatMapMany(snapshot -> {
				recordBacklog(snapshot);
				if (snapshot.totalOutstanding() >= newsPipelineProperties.getDiscoveryBacklogHighWatermark()) {
					appMetrics.jobSkipped(cycleName, "high_backlog");
					log.info(
						"Пропускаем discovery [{}]: backlog={} threshold={}",
						cycleName,
						snapshot.totalOutstanding(),
						newsPipelineProperties.getDiscoveryBacklogHighWatermark()
					);
					return Flux.empty();
				}
				return newsFetchService.fetchNews();
			})
			.doOnComplete(() -> appMetrics.jobSuccess(cycleName, timerSample))
			.onErrorResume(error -> {
				appMetrics.jobFailure(cycleName, timerSample, error);
				log.warn("Ошибка в планировщике задач при получении новостей [{}]", cycleName, error);
				return Flux.empty();
			});
	}

	private void recordBacklog(final NewsPersistenceService.PipelineBacklogSnapshot snapshot) {
		appMetrics.updatePipelineBacklog("content", "pending", snapshot.contentPending());
		appMetrics.updatePipelineBacklog("content", "in_progress", snapshot.contentInProgress());
		appMetrics.updatePipelineBacklog("content", "failed", snapshot.contentFailed());
		appMetrics.updatePipelineBacklog("embedding", "pending", snapshot.embeddingPending());
		appMetrics.updatePipelineBacklog("embedding", "in_progress", snapshot.embeddingInProgress());
		appMetrics.updatePipelineBacklog("embedding", "failed", snapshot.embeddingFailed());
		appMetrics.updatePipelineBacklog("summary", "pending", snapshot.summaryPending());
		appMetrics.updatePipelineBacklog("summary", "in_progress", snapshot.summaryInProgress());
		appMetrics.updatePipelineBacklog("summary", "failed", snapshot.summaryFailed());
	}
}
