package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.ai.summ.SummarizationService;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.ProcessedNews;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NewsSummarizationServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void summarizePersistsSummaryAndMarksStatusDone() {
		final var summarizer = new StubSummarizationService("Краткая выжимка по событию.");
		final var persistence = new StubPersistenceService();
		final var service = new NewsSummarizationService(summarizer, persistence, APP_METRICS);

		final var result = service.summarize(sampleProcessedNews("id-1", "ru")).block();

		assertNotNull(result);
		assertEquals("Краткая выжимка по событию.", result.getContentSummary());
		assertEquals(List.of(
			ProcessingStatus.IN_PROGRESS,
			ProcessingStatus.DONE
		), persistence.statusHistory);
		assertEquals(1, persistence.savedSummaryCalls);
		assertEquals("ru", persistence.savedLang);
		assertEquals("Краткая выжимка по событию.", persistence.savedSummary);
	}

	@Test
	void summarizeFallsBackToTitleWhenModelReturnsBlankSummary() {
		final var summarizer = new StubSummarizationService("   ");
		final var persistence = new StubPersistenceService();
		final var service = new NewsSummarizationService(summarizer, persistence, APP_METRICS);

		final var result = service.summarize(sampleProcessedNews("id-2", "ru")).block();

		assertNotNull(result);
		assertEquals("Title id-2", result.getContentSummary());
		assertEquals("Title id-2", persistence.savedSummary);
		assertEquals("ru", persistence.savedLang);
	}

	private static ProcessedNews sampleProcessedNews(final String id, final String language) {
		return ProcessedNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description")
			.rawContent("Raw content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language(language)
			.build();
	}

	private static final class StubSummarizationService implements SummarizationService {

		private final String response;

		private StubSummarizationService(final String response) {
			this.response = response;
		}

		@Override
		public Mono<String> summarize(final String text, final String lang) {
			return Mono.just(response);
		}
	}

	private static final class StubPersistenceService extends NewsPersistenceService {

		private final List<ProcessingStatus> statusHistory = new ArrayList<>();
		private String savedNewsId;
		private String savedLang;
		private String savedSummary;
		private int savedSummaryCalls;

		private StubPersistenceService() {
			super(null);
		}

		@Override
		public Mono<Void> updateStatusSummary(final String id, final ProcessingStatus status) {
			statusHistory.add(status);
			return Mono.empty();
		}

		@Override
		public Mono<Void> saveNewsSummary(
			final String newsId,
			final String model,
			final String lang,
			final String summary,
			final String promptHash
		) {
			savedNewsId = newsId;
			savedLang = lang;
			savedSummary = summary;
			savedSummaryCalls++;
			return Mono.empty();
		}
	}
}
