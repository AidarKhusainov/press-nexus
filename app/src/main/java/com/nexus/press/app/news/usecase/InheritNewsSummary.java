package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InheritNewsSummary {

	private final NewsRepository newsRepository;
	private final SummarizeNews summarizeNews;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> execute(final ProcessedNews news, final String representativeId) {
		final var timerSample = appMetrics.startStageTimer();
		final String lang = summarizeNews.normalizeLanguage(news.getLanguage());

		return newsRepository.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(newsRepository.findReusableSummary(representativeId, lang))
			.flatMap(cachedSummary -> summarizeNews.persistSummary(
				news,
				lang,
				summarizeNews.normalizeSummary(cachedSummary.summary(), news.getTitle()),
				cachedSummary.model()
			))
			.switchIfEmpty(Mono.defer(() -> summarizeNews.deferSummary(news, "representative summary not ready")))
			.doOnNext(processedNews -> appMetrics.stageSuccess("summarization", timerSample))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("summarization", timerSample, ex);
				return summarizeNews.persistFallback(news, lang, "duplicate fallback");
			});
	}
}
