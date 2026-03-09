package com.nexus.press.app.service.news;

import com.nexus.press.app.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsEmbeddingRecoveryService {

	private static final int EMBEDDING_CONCURRENCY = 2;

	private final NewsPersistenceService newsPersistenceService;
	private final NewsEmbeddingService newsEmbeddingService;
	private final AppMetrics appMetrics;

	public Mono<Long> recoverPendingEmbeddings(final int batchSize) {
		final int safeBatchSize = Math.max(1, batchSize);
		final var timerSample = appMetrics.startJobTimer();

		return newsPersistenceService.findNewsPendingEmbedding(safeBatchSize)
			.flatMap(news -> newsEmbeddingService.embed(news)
				.doOnError(ex -> log.warn("Не удалось восстановить эмбеддинг для новости id={}", news.getId(), ex))
				.onErrorResume(ex -> Mono.empty()), EMBEDDING_CONCURRENCY)
			.count()
			.doOnNext(processed -> {
				appMetrics.jobSuccess("embedding_recovery", timerSample);
				if (processed > 0) {
					log.info("Восстановлены эмбеддинги: {}", processed);
				}
			})
			.doOnError(ex -> appMetrics.jobFailure("embedding_recovery", timerSample, ex));
	}
}
