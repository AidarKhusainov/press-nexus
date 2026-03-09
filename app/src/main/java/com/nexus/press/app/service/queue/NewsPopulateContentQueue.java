package com.nexus.press.app.service.queue;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NewsPopulateContentQueue {

	private static final String QUEUE_NAME = "populate";

	private final Sinks.Many<RawNews> buffer = Sinks.many().multicast().onBackpressureBuffer(10_000);
	private final AppMetrics appMetrics;

	public NewsPopulateContentQueue(final AppMetrics appMetrics) {
		this.appMetrics = appMetrics;
		this.appMetrics.registerQueue(QUEUE_NAME);
	}

	public void add(final RawNews news) {
		final var result = buffer.tryEmitNext(news);
		if (result.isFailure()) {
			appMetrics.queueEmitFailed(QUEUE_NAME);
			log.warn("Failed to emit news: {} reason={}", news.getId(), result);
			return;
		}
		appMetrics.queueEnqueued(QUEUE_NAME);
	}

	public void markConsumed() {
		appMetrics.queueDequeued(QUEUE_NAME);
	}

	public Flux<RawNews> stream() {
		return buffer.asFlux();
	}
}
