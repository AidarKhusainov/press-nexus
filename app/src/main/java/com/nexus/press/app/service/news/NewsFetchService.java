package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.NewsFetchProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsFetchService {

	private final List<NewsFetchProcessor> newsFetchProcessors;
	private final NewsPersistenceService newsPersistenceService;
	private final NewsPipelineProperties newsPipelineProperties;
	private final AppMetrics appMetrics;

	public Flux<RawNews> fetchNews() {
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
		final var req = NewsUpsertRequest.builder()
			.id(news.getId())
			.media(news.getSource().name())
			.externalId(null)
			.url(news.getLink())
			.title(news.getTitle())
			.author(null)
			.language(news.getLanguage())
			.publishedAt(news.getPublishedDate())
			.fetchedAt(OffsetDateTime.now())
			.contentRaw(fallbackContent)
			.contentClean(fallbackContent)
			.statusContent(ProcessingStatus.PENDING)
			.statusEmbedding(ProcessingStatus.PENDING)
			.statusSummary(ProcessingStatus.PENDING)
			.build();

		return newsPersistenceService.upsertDiscovered(req)
			.thenReturn(news.withRawContent(fallbackContent).withCleanContent(fallbackContent));
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
