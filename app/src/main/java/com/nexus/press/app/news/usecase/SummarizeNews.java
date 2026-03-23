package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import java.util.Locale;
import com.nexus.press.app.ai.integration.summ.SummarizationService;
import com.nexus.press.app.ai.integration.summ.SummarizationThrottledException;
import com.nexus.press.app.ai.model.SummarizationResult;
import com.nexus.press.app.ai.model.SummarizationUseCase;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizeNews {

	private static final String FALLBACK_MODEL = "FALLBACK:headline-lead-v1";

	private final SummarizationService summarizationService;
	private final NewsRepository newsRepository;
	private final PlanNewsSummary planNewsSummary;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> execute(
		final ProcessedNews news,
		final NewsCluster cluster,
		final SummarizationUseCase useCase
	) {
		final var timerSample = appMetrics.startStageTimer();
		log.info("Обработка новости с ИИ: id={} title={}", news.getId(), news.getTitle());

		final String lang = normalizeLanguage(news.getLanguage());
		return newsRepository.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(planNewsSummary.execute(news, cluster, lang, useCase))
			.flatMap(plan -> switch (plan.type()) {
				case REUSE_CACHED -> persistSummary(
					news,
					lang,
					normalizeSummary(plan.cachedSummary().summary(), news.getTitle()),
					plan.cachedSummary().model()
				);
				case USE_PROVIDER -> summarizeWithProvider(news, lang, useCase);
				case USE_FALLBACK -> persistFallback(news, lang, plan.reason());
				case DEFER -> deferSummary(news, plan.reason());
			})
			.doOnNext(processedNews -> appMetrics.stageSuccess("summarization", timerSample))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("summarization", timerSample, ex);
				if (ex instanceof SummarizationThrottledException) {
					return persistFallback(news, lang, ex.getMessage());
				}
				log.warn("Сбой суммаризации: id={} title={}", news.getId(), news.getTitle(), ex);
				return persistFallback(news, lang, "provider failure");
			});
	}

	String normalizeLanguage(final String language) {
		if (language == null || language.isBlank()) {
			return "ru";
		}
		final String normalized = language.strip().toLowerCase(Locale.ROOT);
		if (normalized.startsWith("ru")) {
			return "ru";
		}
		if (normalized.startsWith("en")) {
			return "en";
		}
		if (normalized.startsWith("es")) {
			return "es";
		}
		return "ru";
	}

	String normalizeSummary(final String summary, final String fallbackTitle) {
		if (summary != null && !summary.isBlank()) {
			return summary.replaceAll("\\s+", " ").strip();
		}
		if (fallbackTitle != null && !fallbackTitle.isBlank()) {
			return fallbackTitle.strip();
		}
		return "Краткая сводка недоступна";
	}

	Mono<ProcessedNews> persistSummary(
		final ProcessedNews news,
		final String lang,
		final String summary,
		final String modelName
	) {
		return newsRepository.saveNewsSummary(news.getId(), modelName, lang, summary, null)
			.then(newsRepository.updateStatusSummary(news.getId(), ProcessingStatus.DONE))
			.thenReturn(withSummary(news, summary))
			.doOnNext(savedNews -> log.info(
				"Новость до и после суммаризации: id={} title={} model={} \nДО: {}\n\nПОСЛЕ: \n{}\n",
				news.getId(),
				news.getTitle(),
				modelName,
				contentForSummary(news),
				summary
			));
	}

	Mono<ProcessedNews> persistFallback(final ProcessedNews news, final String lang, final String reason) {
		final String summary = fallbackSummary(news);
		log.warn("Используем cheap fallback для summary: id={} title={} reason={}", news.getId(), news.getTitle(), reason);
		return persistSummary(news, lang, summary, FALLBACK_MODEL);
	}

	Mono<ProcessedNews> deferSummary(final ProcessedNews news, final String reason) {
		log.info("Суммаризация отложена: id={} title={} reason={}", news.getId(), news.getTitle(), reason);
		return newsRepository.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(Mono.empty());
	}

	private Mono<ProcessedNews> summarizeWithProvider(
		final ProcessedNews news,
		final String lang,
		final SummarizationUseCase useCase
	) {
		return summarizationService.summarizeDetailed(contentForSummary(news), lang, useCase)
			.map(result -> new SummarizationResult(
				normalizeSummary(result.summary(), news.getTitle()),
				result.modelName()
			))
			.flatMap(result -> persistSummary(news, lang, result.summary(), result.modelName()));
	}

	private String fallbackSummary(final ProcessedNews news) {
		final String title = normalizeSummary(null, news.getTitle());
		final String content = normalizeContentForFallback(contentForSummary(news));
		if (content.isBlank()) {
			return title;
		}

		final String firstSentence = firstSentence(content);
		if (firstSentence.isBlank()) {
			return title;
		}
		if (firstSentence.equalsIgnoreCase(title)) {
			return title;
		}
		return title + ". " + firstSentence;
	}

	private String normalizeContentForFallback(final String content) {
		if (content == null || content.isBlank()) {
			return "";
		}
		return content.replaceAll("\\s+", " ").strip();
	}

	private String firstSentence(final String content) {
		final int maxChars = 220;
		final int dotIndex = content.indexOf('.');
		final int exclamationIndex = content.indexOf('!');
		final int questionIndex = content.indexOf('?');
		int end = content.length();
		for (final int idx : new int[] {dotIndex, exclamationIndex, questionIndex}) {
			if (idx >= 0) {
				end = Math.min(end, idx + 1);
			}
		}
		end = Math.min(end, maxChars);
		return content.substring(0, end).strip();
	}

	private ProcessedNews withSummary(final ProcessedNews source, final String summary) {
		return ProcessedNews.builder()
			.id(source.getId())
			.link(source.getLink())
			.title(source.getTitle())
			.description(source.getDescription())
			.rawContent(source.getRawContent())
			.cleanContent(source.getCleanContent())
			.source(source.getSource())
			.publishedDate(source.getPublishedDate())
			.fetchedDate(source.getFetchedDate())
			.language(source.getLanguage())
			.contentSummary(summary)
			.build();
	}

	private String contentForSummary(final ProcessedNews news) {
		if (news.getCleanContent() != null && !news.getCleanContent().isBlank()) {
			return news.getCleanContent();
		}
		return news.getRawContent();
	}
}
