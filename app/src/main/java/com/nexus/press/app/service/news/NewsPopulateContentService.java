package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsPopulateContentProcessor;
import com.nexus.press.app.service.queue.NewsPopulateContentQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class NewsPopulateContentService {

	private final List<NewsPopulateContentProcessor> populateContentProcessors;
	private final NewsPopulateContentQueue newsPopulateContentQueue;
	private final NewsPersistenceService newsPersistenceService;
	private final AppMetrics appMetrics;

	public NewsPopulateContentService(
		final List<NewsPopulateContentProcessor> populateContentProcessors,
		final NewsPopulateContentQueue newsPopulateContentQueue,
		final NewsPersistenceService newsPersistenceService,
		final AppMetrics appMetrics
	) {
		this.populateContentProcessors = populateContentProcessors.stream()
			.sorted(Comparator.comparingInt(NewsPopulateContentProcessor::getPriority).reversed())
			.toList();
		this.newsPopulateContentQueue = newsPopulateContentQueue;
		this.newsPersistenceService = newsPersistenceService;
		this.appMetrics = appMetrics;
	}

	public Mono<RawNews> populate(final RawNews rawNews) {
		final var timerSample = appMetrics.startStageTimer();
		return Mono.just(rawNews)
			.flatMap(news -> {
				final var newsPopulateContentProcessor = resolveProcessor(news);
				if (newsPopulateContentProcessor != null) {
					return newsPopulateContentProcessor.process(news)
						.onErrorResume(ex -> {
							log.warn("Не удалось получить полный контент {}: id={} title={}",
								news.getSource(), news.getId(), news.getTitle(), ex);
							return Mono.just(news);
						});
				}
				return Mono.just(news);
			})
			.map(this::withFallbackContent)
			.flatMap(this::persist)
			.timeout(Duration.ofMinutes(5))
			.doOnNext(news -> {
				newsPopulateContentQueue.add(news);
				appMetrics.stageSuccess("populate", timerSample);
			})
			.onErrorResume(ex -> {
				appMetrics.stageFailure("populate", timerSample, ex);
				log.error("Ошибка при заполнении новости: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsPersistenceService.updateStatusContent(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}

	private NewsPopulateContentProcessor resolveProcessor(final RawNews news) {
		for (final var processor : populateContentProcessors) {
			if (processor.supports(news)) return processor;
		}
		return null;
	}

	private RawNews withFallbackContent(final RawNews news) {
		if (StringUtils.hasText(news.getRawContent())) return news;
		return news.withRawContent(news.getDescription());
	}

	private Mono<RawNews> persist(final RawNews news) {
		final var req = NewsUpsertRequest.builder()
			.id(news.getId())
			.media(news.getSource().name())
			.externalId(null)
			.url(news.getLink())
			.title(news.getTitle())
			.author(null)
			.language(news.getLanguage())
			.publishedAt(news.getPublishedDate())
			.fetchedAt(java.time.OffsetDateTime.now())
			.contentRaw(news.getRawContent())
			.contentClean(news.getRawContent())
			.statusContent(ProcessingStatus.DONE)
			.statusEmbedding(ProcessingStatus.PENDING)
			.statusSummary(ProcessingStatus.PENDING)
			.build();

		return newsPersistenceService.upsert(req)
			.map(saved -> RawNews.builder()
				.id(saved.getId())
				.link(news.getLink())
				.title(news.getTitle())
				.description(news.getDescription())
				.rawContent(news.getRawContent())
				.source(news.getSource())
				.publishedDate(news.getPublishedDate())
				.language(news.getLanguage())
				.build());
	}
}
