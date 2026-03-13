package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import com.nexus.press.app.service.ai.embed.EmbeddingService;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.ProcessedNews;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsEmbeddingService {

	@Qualifier("ollamaNomicEmbeddingService")
	private final EmbeddingService embeddingService;
	private final ReactiveNewsSimilarityStore similarityStore;
	private final NewsPersistenceService newsPersistenceService;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> embed(final RawNews rawNews) {
		final var timerSample = appMetrics.startStageTimer();
		log.info("Получение эмбеддинга новости: id={} title={}", rawNews.getId(), rawNews.getTitle());

		return newsPersistenceService.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.IN_PROGRESS)
			.then(embeddingService.embed(rawNews.getRawContent()))
			.timeout(Duration.ofMinutes(5))
			.flatMap(embedding -> {
				log.info("Готов эмбеддинг: id={} dims={}", rawNews.getId(), embedding.length);
				return similarityStore.upsertEmbedding(rawNews.getId(), embedding)
					.thenReturn(ProcessedNews.builder()
						.id(rawNews.getId())
						.link(rawNews.getLink())
						.title(rawNews.getTitle())
						.description(rawNews.getDescription())
						.rawContent(rawNews.getRawContent())
						.source(rawNews.getSource())
						.publishedDate(rawNews.getPublishedDate())
						.language(rawNews.getLanguage())
						.build());
			})
			.flatMap(processed -> newsPersistenceService.updateStatusEmbedding(processed.getId(), ProcessingStatus.DONE)
				.thenReturn(processed))
			.doOnNext(news -> appMetrics.stageSuccess("embedding", timerSample))
			.doOnSubscribe(s -> log.info("Старт эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle()))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("embedding", timerSample, ex);
				log.warn("Сбой эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsPersistenceService.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			})
			.name("embed-news")
			.tag("newsId", String.valueOf(rawNews.getId()));
	}

	public Mono<List<ProcessedNews>> embedBatch(final List<RawNews> batch) {
		if (batch == null || batch.isEmpty()) {
			return Mono.just(List.of());
		}

		final List<Timer.Sample> timerSamples = batch.stream()
			.map(news -> appMetrics.startStageTimer())
			.toList();

		log.info("Старт batch-эмбеддинга: size={}", batch.size());

		return embeddingService.embedBatch(batch.stream().map(RawNews::getRawContent).toList())
			.flatMapMany(embeddings -> persistBatch(batch, embeddings, timerSamples))
			.collectList()
			.onErrorResume(ex -> {
				log.warn("Batch-эмбеддинг не удался, переключаемся на одиночную обработку: size={}", batch.size(), ex);
				return Flux.fromIterable(batch)
					.concatMap(this::embed)
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
			.concatMap(index -> persistEmbedded(batch.get(index), embeddings.get(index), timerSamples.get(index)));
	}

	private Mono<ProcessedNews> persistEmbedded(final RawNews rawNews, final float[] embedding, final Timer.Sample timerSample) {
		log.info("Готов batch-эмбеддинг: id={} dims={}", rawNews.getId(), embedding.length);

		return similarityStore.upsertEmbedding(rawNews.getId(), embedding)
			.thenReturn(ProcessedNews.builder()
				.id(rawNews.getId())
				.link(rawNews.getLink())
				.title(rawNews.getTitle())
				.description(rawNews.getDescription())
				.rawContent(rawNews.getRawContent())
				.source(rawNews.getSource())
				.publishedDate(rawNews.getPublishedDate())
				.language(rawNews.getLanguage())
				.build())
			.flatMap(processed -> newsPersistenceService.updateStatusEmbedding(processed.getId(), ProcessingStatus.DONE)
				.thenReturn(processed))
			.doOnNext(news -> appMetrics.stageSuccess("embedding", timerSample))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("embedding", timerSample, ex);
				log.warn("Сбой batch-эмбединга: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsPersistenceService.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}
}
