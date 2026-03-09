package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsFetchProcessor;
import com.nexus.press.app.service.queue.NewsFetchQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsFetchService {

	private final List<NewsFetchProcessor> newsFetchProcessors;
	private final NewsFetchQueue newsFetchQueue;
	private final AppMetrics appMetrics;

	public Flux<RawNews> fetchNews() {
		return Flux.fromIterable(newsFetchProcessors)
			.flatMap(processor -> {
				final var timerSample = appMetrics.startStageTimer();
				return processor.fetchNews()
					.timeout(Duration.ofMinutes(5))
					.doOnNext(newsFetchQueue::add)
					.doOnComplete(() -> appMetrics.stageSuccess("fetch", timerSample))
					.onErrorResume(ex -> {
						appMetrics.stageFailure("fetch", timerSample, ex);
						log.error("Ошибка при получении новостей через {}", processor.getClass().getSimpleName(), ex);
						return Mono.empty();
					});
			});
	}
}
