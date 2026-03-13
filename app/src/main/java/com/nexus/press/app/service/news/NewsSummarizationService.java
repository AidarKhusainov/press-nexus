package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.util.Locale;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.ai.summ.SummarizationService;
import com.nexus.press.app.service.news.model.ProcessedNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSummarizationService {

	private final SummarizationService summarizationService;
	private final NewsPersistenceService newsPersistenceService;
	private final AppMetrics appMetrics;

	public Mono<ProcessedNews> summarize(final ProcessedNews news) {
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
		final String modelName = summarizeModelName();

		return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.IN_PROGRESS)
			.then(summarizationService.summarize(news.getRawContent(), lang))
			.map(summary -> normalizeSummary(summary, news.getTitle()))
			.flatMap(summary -> newsPersistenceService
				.saveNewsSummary(news.getId(), modelName, lang, summary, null)
				.thenReturn(summary))
			.map(summary -> {
				log.info("Новость до и после суммаризации: id={} title={} \nДО: {}\n\nПОСЛЕ: \n{}\n",
					news.getId(), news.getTitle(), news.getRawContent(), summary);
				return withSummary(news, summary);
			})
			.flatMap(n -> newsPersistenceService.updateStatusSummary(n.getId(), ProcessingStatus.DONE)
				.thenReturn(n))
			.doOnNext(processedNews -> appMetrics.stageSuccess("summarization", timerSample))
			.onErrorResume(ex -> {
				appMetrics.stageFailure("summarization", timerSample, ex);
				log.warn("Сбой суммаризации: id={} title={}", news.getId(), news.getTitle(), ex);
				return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}

	private String summarizeModelName() {
		return summarizationService.modelName();
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

	private ProcessedNews withSummary(final ProcessedNews source, final String summary) {
		return ProcessedNews.builder()
			.id(source.getId())
			.link(source.getLink())
			.title(source.getTitle())
			.description(source.getDescription())
			.rawContent(source.getRawContent())
			.source(source.getSource())
			.publishedDate(source.getPublishedDate())
			.language(source.getLanguage())
			.contentSummary(summary)
			.build();
	}
}
