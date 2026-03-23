package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.ai.integration.embed.EmbeddingService;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedNews {

	@Qualifier("ollamaNomicEmbeddingService")
	private final EmbeddingService embeddingService;
	private final NewsSimilarityRepository newsSimilarityRepository;
	private final NewsRepository newsRepository;
	private final AppMetrics appMetrics;
	private final PipelineRuntimeStats pipelineRuntimeStats;

	public Mono<ProcessedNews> execute(final RawNews rawNews) {
		final var timerSample = appMetrics.startStageTimer();
		log.info("Получение эмбеддинга новости: id={} title={}", rawNews.getId(), rawNews.getTitle());

		return newsRepository.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.IN_PROGRESS)
			.then(embeddingService.embed(contentForEmbedding(rawNews)))
			.timeout(Duration.ofMinutes(5))
			.flatMap(embedding -> persistEmbedded(rawNews, embedding, timerSample))
			.doOnSubscribe(s -> log.info("Старт эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle()))
			.name("embed-news")
			.tag("newsId", String.valueOf(rawNews.getId()));
	}

	String contentForEmbedding(final RawNews news) {
		if (news.getCleanContent() != null && !news.getCleanContent().isBlank()) {
			return news.getCleanContent();
		}
		return news.getRawContent();
	}

	ProcessedNews toProcessedNews(final RawNews rawNews) {
		return ProcessedNews.builder()
			.id(rawNews.getId())
			.link(rawNews.getLink())
			.title(rawNews.getTitle())
			.description(rawNews.getDescription())
			.rawContent(rawNews.getRawContent())
			.cleanContent(rawNews.getCleanContent())
			.source(rawNews.getSource())
			.publishedDate(rawNews.getPublishedDate())
			.fetchedDate(rawNews.getFetchedDate())
			.language(rawNews.getLanguage())
			.build();
	}

	Mono<ProcessedNews> persistEmbedded(final RawNews rawNews, final float[] embedding, final Timer.Sample timerSample) {
		log.info("Готов эмбеддинг: id={} dims={}", rawNews.getId(), embedding.length);

		return newsSimilarityRepository.upsertEmbedding(rawNews.getId(), embedding)
			.thenReturn(toProcessedNews(rawNews))
			.flatMap(processed -> newsRepository.updateStatusEmbedding(processed.getId(), ProcessingStatus.DONE)
				.thenReturn(processed))
			.doOnNext(news -> {
				appMetrics.stageSuccess("embedding", timerSample);
				pipelineRuntimeStats.recordEmbeddedNews();
			})
			.onErrorResume(ex -> {
				appMetrics.stageFailure("embedding", timerSample, ex);
				log.warn("Сбой эмбеддинга: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsRepository.updateStatusEmbedding(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}
}
