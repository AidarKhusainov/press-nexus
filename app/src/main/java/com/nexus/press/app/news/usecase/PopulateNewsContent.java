package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.integration.NewsPopulateContentProcessor;
import com.nexus.press.app.news.policy.NewsContentCleaner;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class PopulateNewsContent {

	private final List<NewsPopulateContentProcessor> populateContentProcessors;
	private final NewsContentCleaner newsContentCleaner;
	private final UpsertNews upsertNews;
	private final NewsRepository newsRepository;
	private final AppMetrics appMetrics;
	private final PipelineRuntimeStats pipelineRuntimeStats;

	public PopulateNewsContent(
		final List<NewsPopulateContentProcessor> populateContentProcessors,
		final NewsContentCleaner newsContentCleaner,
		final UpsertNews upsertNews,
		final NewsRepository newsRepository,
		final AppMetrics appMetrics,
		final PipelineRuntimeStats pipelineRuntimeStats
	) {
		this.populateContentProcessors = populateContentProcessors.stream()
			.sorted(Comparator.comparingInt(NewsPopulateContentProcessor::getPriority).reversed())
			.toList();
		this.newsContentCleaner = newsContentCleaner;
		this.upsertNews = upsertNews;
		this.newsRepository = newsRepository;
		this.appMetrics = appMetrics;
		this.pipelineRuntimeStats = pipelineRuntimeStats;
	}

	public Mono<RawNews> execute(final RawNews rawNews) {
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
				appMetrics.stageSuccess("populate", timerSample);
				pipelineRuntimeStats.recordPopulatedNews();
			})
			.onErrorResume(ex -> {
				appMetrics.stageFailure("populate", timerSample, ex);
				log.error("Ошибка при заполнении новости: id={} title={}", rawNews.getId(), rawNews.getTitle(), ex);
				return newsRepository.updateStatusContent(rawNews.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}

	private NewsPopulateContentProcessor resolveProcessor(final RawNews news) {
		for (final var processor : populateContentProcessors) {
			if (processor.supports(news)) {
				return processor;
			}
		}
		return null;
	}

	private RawNews withFallbackContent(final RawNews news) {
		if (StringUtils.hasText(news.getRawContent())) {
			return news;
		}
		return news.withRawContent(news.getDescription());
	}

	private Mono<RawNews> persist(final RawNews news) {
		final String cleanContent = newsContentCleaner.clean(news);
		final var req = NewsUpsertRequest.builder()
			.id(news.getId())
			.media(news.getSource().name())
			.externalId(news.getExternalId())
			.url(news.getLink())
			.title(news.getTitle())
			.author(null)
			.language(news.getLanguage())
			.publishedAt(news.getPublishedDate())
			.fetchedAt(java.time.OffsetDateTime.now())
			.contentRaw(news.getRawContent())
			.contentClean(cleanContent)
			.statusContent(ProcessingStatus.DONE)
			.statusEmbedding(ProcessingStatus.PENDING)
			.statusSummary(ProcessingStatus.PENDING)
			.build();

		return upsertNews.execute(req)
			.map(savedId -> RawNews.builder()
				.id(savedId)
				.externalId(news.getExternalId())
				.link(news.getLink())
				.title(news.getTitle())
				.description(news.getDescription())
				.rawContent(news.getRawContent())
				.cleanContent(cleanContent)
				.source(news.getSource())
				.publishedDate(news.getPublishedDate())
				.fetchedDate(news.getFetchedDate())
				.language(news.getLanguage())
				.build());
	}
}
