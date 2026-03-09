package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import com.nexus.press.app.service.news.NewsEmbeddingRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledEmbeddingRecoveryTask {

	private static final int RECOVERY_BATCH_SIZE = 200;
	private static final Duration RECOVERY_INTERVAL = Duration.ofMinutes(2);

	private final NewsEmbeddingRecoveryService newsEmbeddingRecoveryService;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void startRecovery() {
			subscription = Flux.interval(Duration.ZERO, RECOVERY_INTERVAL)
				.concatMap(tick -> newsEmbeddingRecoveryService.recoverPendingEmbeddings(RECOVERY_BATCH_SIZE)
					.onErrorResume(error -> {
						log.warn("Ошибка в восстановителе эмбеддингов на тике {}", tick, error);
						return Mono.empty();
					}))
				.doOnSubscribe(s -> log.info("Запущен восстановитель эмбеддингов"))
				.subscribe(
					null,
					error -> log.error("Восстановитель эмбеддингов остановлен из-за необработанной ошибки", error)
				);
	}

	@PreDestroy
	public void stopRecovery() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Embedding recovery scheduler stopped");
		}
	}
}
