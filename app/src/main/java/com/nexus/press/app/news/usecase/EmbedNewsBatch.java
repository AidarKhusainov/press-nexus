package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.ai.integration.embed.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedNewsBatch {

	@Qualifier("ollamaNomicEmbeddingService")
	private final EmbeddingService embeddingService;
	private final EmbedNews embedNews;
	private final AppMetrics appMetrics;

	public Mono<List<ProcessedNews>> execute(final List<RawNews> batch) {
		if (batch == null || batch.isEmpty()) {
			return Mono.just(List.of());
		}

		final List<Timer.Sample> timerSamples = batch.stream()
			.map(news -> appMetrics.startStageTimer())
			.toList();

		log.info("Старт batch-эмбеддинга: size={}", batch.size());

		return embeddingService.embedBatch(batch.stream().map(embedNews::contentForEmbedding).toList())
			.flatMapMany(embeddings -> persistBatch(batch, embeddings, timerSamples))
			.collectList()
			.onErrorResume(ex -> {
				log.warn("Batch-эмбеддинг не удался, переключаемся на одиночную обработку: size={}", batch.size(), ex);
				return Flux.fromIterable(batch)
					.concatMap(embedNews::execute)
					.collectList();
			});
	}

	private Flux<ProcessedNews> persistBatch(
		final List<RawNews> batch,
		final List<float[]> embeddings,
		final List<Timer.Sample> timerSamples
	) {
		if (embeddings.size() != batch.size()) {
			return Flux.error(new IllegalStateException(
				"Expected " + batch.size() + " embeddings but got " + embeddings.size()
			));
		}

		return Flux.range(0, batch.size())
			.concatMap(index -> embedNews.persistEmbedded(batch.get(index), embeddings.get(index), timerSamples.get(index)));
	}
}
