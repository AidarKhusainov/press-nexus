package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.util.Locale;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.ai.summ.SummarizationResult;
import com.nexus.press.app.service.ai.summ.SummarizationService;
import com.nexus.press.app.service.ai.summ.SummarizationUseCase;
import com.nexus.press.app.service.ai.summ.SummarizationThrottledException;
import com.nexus.press.app.service.news.model.ProcessedNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSummarizationService {

	private static final String FALLBACK_MODEL = "FALLBACK:headline-lead-v1";

	private final SummarizationService summarizationService;
	private final NewsPersistenceService newsPersistenceService;
	private final NewsSummaryPlanner newsSummaryPlanner;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> summarize(final ProcessedNews news) {
		return summarize(news, new NewsClusteringService.Cluster(java.util.Set.of(news.getId()), news.getId()), SummarizationUseCase.AUTO_CLUSTER);
	}

	public Mono<ProcessedNews> summarize(
		final ProcessedNews news,
		final NewsClusteringService.Cluster cluster,
		final SummarizationUseCase useCase
	) {
		final var timerSample = appMetrics.startStageTimer();
		log.info("Обработка новости с ИИ: id={} title={}", news.getId(), news.getTitle());
		// TODO Сделать сначала отдельный эмбеддинг новостей, чтобы найти одинаковые новости, но с разных ресурсов, а потом суммаризацию
		//  Отправлять сразу несколько новостей в один чат для суммаризации
		//  Ollama может выдавать Structured Outputs в виде json
		/*
		 * {
		 * 	items: [
		 * 		{
		 * 		summarize: "",
		 * 		tags: "",
		 * 		...
		 * 		},
		 * 	]
		 * }
		 * */
		//  Можно еще кастомизировать модель под суммаризицаю ollama create -f Modelfile
		//  Почитай Ollama FAQ

		final String lang = normalizeLanguage(news.getLanguage());
		return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(newsSummaryPlanner.planForRepresentative(news, cluster, lang, useCase))
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

	public Mono<ProcessedNews> inheritSummary(final ProcessedNews news, final String representativeId) {
		final var timerSample = appMetrics.startStageTimer();
		final String lang = normalizeLanguage(news.getLanguage());

		return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(newsPersistenceService.findReusableSummary(representativeId, news.getContentHash(), lang))
			.flatMap(cachedSummary -> persistSummary(
				news,
				lang,
				normalizeSummary(cachedSummary.summary(), news.getTitle()),
				cachedSummary.model()
			))
			.switchIfEmpty(Mono.defer(() -> deferSummary(news, "representative summary not ready")))
			.doOnNext(processedNews -> appMetrics.stageSuccess("summarization", timerSample))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("summarization", timerSample, ex);
				return persistFallback(news, lang, "duplicate fallback");
			});
	}

	private String normalizeLanguage(final String language) {
		if (language == null || language.isBlank()) return "ru";
		final String normalized = language.strip().toLowerCase(Locale.ROOT);
		if (normalized.startsWith("ru")) return "ru";
		if (normalized.startsWith("en")) return "en";
		if (normalized.startsWith("es")) return "es";
		return "ru";
	}

	private String normalizeSummary(final String summary, final String fallbackTitle) {
		if (summary != null && !summary.isBlank()) {
			return summary.replaceAll("\\s+", " ").strip();
		}
		if (fallbackTitle != null && !fallbackTitle.isBlank()) {
			return fallbackTitle.strip();
		}
		return "Краткая сводка недоступна";
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

	private Mono<ProcessedNews> persistSummary(
		final ProcessedNews news,
		final String lang,
		final String summary,
		final String modelName
	) {
		return newsPersistenceService
			.saveNewsSummary(news.getId(), modelName, lang, summary, null)
			.then(newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.DONE))
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

	private Mono<ProcessedNews> persistFallback(final ProcessedNews news, final String lang, final String reason) {
		final String summary = fallbackSummary(news);
		log.warn("Используем cheap fallback для summary: id={} title={} reason={}", news.getId(), news.getTitle(), reason);
		return persistSummary(news, lang, summary, FALLBACK_MODEL);
	}

	private Mono<ProcessedNews> deferSummary(final ProcessedNews news, final String reason) {
		log.info("Суммаризация отложена: id={} title={} reason={}", news.getId(), news.getTitle(), reason);
		return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(Mono.empty());
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
			.contentHash(source.getContentHash())
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
