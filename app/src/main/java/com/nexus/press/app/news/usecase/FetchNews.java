package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.news.integration.NewsFetchProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchNews {

	private final List<NewsFetchProcessor> newsFetchProcessors;
	private final NewsRepository newsRepository;
	private final NewsPipelineProperties newsPipelineProperties;
	private final AppMetrics appMetrics;

	public Flux<RawNews> execute() {
		return Flux.fromIterable(newsFetchProcessors)
			.concatMap(processor -> {
				final var timerSample = appMetrics.startStageTimer();
				return processor.fetchNews()
					.timeout(Duration.ofMinutes(5))
					.flatMap(this::persistDiscoveredNews, Math.max(1, newsPipelineProperties.getDiscoveryPersistConcurrency()), 1)
					.doOnComplete(() -> appMetrics.stageSuccess("fetch", timerSample))
					.onErrorResume(ex -> {
						appMetrics.stageFailure("fetch", timerSample, ex);
						log.error("Ошибка при получении новостей через {}", processor.getClass().getSimpleName(), ex);
						return Mono.empty();
					});
			});
	}

	private Mono<RawNews> persistDiscoveredNews(final RawNews news) {
		final String fallbackContent = discoveryContent(news);
		final String requestId = news.getId() != null ? news.getId() : UUID.randomUUID().toString();
		final OffsetDateTime fetchedAt = OffsetDateTime.now();
		final var req = NewsUpsertRequest.builder()
			.id(requestId)
			.media(news.getSource().name())
			.externalId(news.getExternalId())
			.url(news.getLink())
			.title(news.getTitle())
			.author(null)
			.language(news.getLanguage())
			.publishedAt(news.getPublishedDate())
			.fetchedAt(fetchedAt)
			.contentRaw(fallbackContent)
			.contentClean(fallbackContent)
			.statusContent(ProcessingStatus.PENDING)
			.statusEmbedding(ProcessingStatus.PENDING)
			.statusSummary(ProcessingStatus.PENDING)
			.build();

		return newsRepository.saveDiscoveredIfAbsent(req, requestId, fetchedAt)
			.map(saved -> new RawNews(
				news.getId() != null ? news.getId() : saved.getId(),
				news.getExternalId(),
				news.getLink(),
				news.getTitle(),
				news.getDescription(),
				fallbackContent,
				fallbackContent,
				news.getSource(),
				news.getPublishedDate(),
				news.getFetchedDate(),
				news.getLanguage()
			))
				.doOnSuccess(savedNews -> {
					if (savedNews != null) {
						return;
					}
					log.debug(
						"Пропускаем уже сохраненную RSS новость: source={} externalId={} url={}",
						news.getSource(),
						news.getExternalId(),
						news.getLink()
					);
				});
	}

	private String discoveryContent(final RawNews news) {
		if (StringUtils.hasText(news.getDescription())) {
			return news.getDescription().strip();
		}
		if (StringUtils.hasText(news.getTitle())) {
			return news.getTitle().strip();
		}
		return news.getLink();
	}
}
