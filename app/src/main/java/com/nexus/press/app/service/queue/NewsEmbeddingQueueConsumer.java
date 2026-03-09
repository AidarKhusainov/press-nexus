package com.nexus.press.app.service.queue;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import com.nexus.press.app.service.news.NewsSimilarityService;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.service.news.NewsClusteringService;
import com.nexus.press.app.service.news.NewsSummarizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NewsEmbeddingQueueConsumer {

	private final Disposable subscription;

    public NewsEmbeddingQueueConsumer(
        final NewsEmbeddingQueue queue,
        final NewsSummarizationService newsSummarizationService,
        final NewsSimilarityService newsSimilarityService,
        final SimilarityProperties similarityProperties,
        final NewsClusteringService newsClusteringService
    ) {
        this.subscription = queue.stream()
            .onBackpressureBuffer(5_000)
            .doOnNext(news -> queue.markConsumed())
            .flatMap(n -> newsSimilarityService.observe(n, similarityProperties.getTopN(), similarityProperties.getMinScore())
                .then(newsClusteringService.clusterOf(n.getId(), similarityProperties.getClusterMinScore()))
                .flatMap(cluster -> {
                    final var repr = cluster.representativeId();
                    if (repr == null || !repr.equals(n.getId())) {
                        return reactor.core.publisher.Mono.empty();
                    }
                    return newsSummarizationService.summarize(n);
                })
            )
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
