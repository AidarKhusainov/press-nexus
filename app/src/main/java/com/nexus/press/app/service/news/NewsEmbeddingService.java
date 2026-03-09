package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.time.Duration;
import com.nexus.press.app.service.ai.embed.EmbeddingService;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.ProcessedNews;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.queue.NewsEmbeddingQueue;
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
	private final NewsEmbeddingQueue newsEmbeddingQueue;
	private final NewsPersistenceService newsPersistenceService;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> embed(final RawNews rawNews) {
		final var timerSample = appMetrics.startStageTimer();
		log.info("Получение эмбеддинга новости: id={} title={}", rawNews.getId(), rawNews.getTitle());

		return newsPersistenceService.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.IN_PROGRESS)
			.then(embeddingService.embed(rawNews.getRawContent()))
			.timeout(Duration.ofMinutes(5))
			.map( embedding -> {
				final ProcessedNews processedNews = ProcessedNews.builder()
					.id(rawNews.getId())
					.link(rawNews.getLink())
					.title(rawNews.getTitle())
					.description(rawNews.getDescription())
					.rawContent(rawNews.getRawContent())
					.source(rawNews.getSource())
					.publishedDate(rawNews.getPublishedDate())
					.language(rawNews.getLanguage())
					.contentEmbedding(embedding)
					.build();
				return processedNews;
			})
			.flatMap(processed -> newsPersistenceService.updateStatusEmbedding(processed.getId(), ProcessingStatus.DONE)
				.thenReturn(processed))
			.doOnNext(news -> {
				newsEmbeddingQueue.add(news);
				appMetrics.stageSuccess("embedding", timerSample);
			})
			.doOnSubscribe(s -> log.info("Старт эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle()))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("embedding", timerSample, ex);
				log.warn("Сбой эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsPersistenceService.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			})
			.doOnSuccess(processedNews -> {
				if (processedNews != null) {
					log.info("Готов эмбеддинг: id={} dims={}", processedNews.getId(), processedNews.getContentEmbedding().length);
				}
			})
			.name("embed-news")
			.tag("newsId", String.valueOf(rawNews.getId()));
	}
}
