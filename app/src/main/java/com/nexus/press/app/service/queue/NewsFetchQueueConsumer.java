package com.nexus.press.app.service.queue;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import com.nexus.press.app.service.news.NewsPopulateContentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NewsFetchQueueConsumer {

	private final Disposable subscription;

	public NewsFetchQueueConsumer(final NewsFetchQueue queue, final NewsPopulateContentService newsPopulateContentService) {
		this.subscription = queue.stream()
			.onBackpressureBuffer(5_000)
			.doOnNext(news -> queue.markConsumed())
			.flatMap(newsPopulateContentService::populate)
			.subscribe(
				null,
				ex -> log.error("Fatal error in news stream", ex),
				() -> log.info("News stream completed")
			);
	}

	@PreDestroy
	public void stop() {
		if (subscription != null) {
			subscription.dispose();
			log.info("Consumer stopped");
		}
	}
}
