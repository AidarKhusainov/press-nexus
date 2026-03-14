package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.ai.summ.SummarizationResult;
import com.nexus.press.app.service.ai.summ.SummarizationService;
import com.nexus.press.app.service.ai.summ.SummarizationUseCase;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.ProcessedNews;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NewsSummarizationServiceTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void summarizePersistsSummaryAndMarksStatusDone() {
		final var summarizer = new StubSummarizationService("Краткая выжимка по событию.");
		final var persistence = new StubPersistenceService();
		final var planner = planner(NewsSummaryPlanner.SummaryPlan.useProvider());
		final var service = new NewsSummarizationService(summarizer, persistence, planner, APP_METRICS);

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
		assertEquals("Clean content id-1", summarizer.lastText);
	}

	@Test
	void summarizeFallsBackToTitleWhenModelReturnsBlankSummary() {
		final var summarizer = new StubSummarizationService("   ");
		final var persistence = new StubPersistenceService();
		final var planner = planner(NewsSummaryPlanner.SummaryPlan.useProvider());
		final var service = new NewsSummarizationService(summarizer, persistence, planner, APP_METRICS);

		final var result = service.summarize(sampleProcessedNews("id-2", "ru")).block();

		assertNotNull(result);
		assertEquals("Title id-2", result.getContentSummary());
		assertEquals("Title id-2", persistence.savedSummary);
		assertEquals("ru", persistence.savedLang);
		assertEquals("Clean content id-2", summarizer.lastText);
	}

	@Test
	void summarizeUsesFallbackWhenProviderFails() {
		final var summarizer = new ErroringSummarizationService(new RuntimeException("quota exhausted"));
		final var persistence = new StubPersistenceService();
		final var planner = planner(NewsSummaryPlanner.SummaryPlan.useProvider());
		final var service = new NewsSummarizationService(summarizer, persistence, planner, APP_METRICS);

		final var result = service.summarize(sampleProcessedNews("id-3", "ru")).block();

		assertNotNull(result);
		assertEquals("Title id-3. Clean content id-3", result.getContentSummary());
		assertEquals(List.of(
			ProcessingStatus.IN_PROGRESS,
			ProcessingStatus.DONE
		), persistence.statusHistory);
		assertEquals(1, persistence.savedSummaryCalls);
		assertEquals("FALLBACK:headline-lead-v1", persistence.savedModel);
	}

	@Test
	void summarizeReusesCachedSummaryWithoutProviderCall() {
		final var summarizer = mock(SummarizationService.class);
		final var persistence = new StubPersistenceService();
		final var planner = planner(NewsSummaryPlanner.SummaryPlan.reuseCached(
			new NewsPersistenceService.CachedSummary("GEMINI:gemini-2.5-flash", "ru", "Cached summary"),
			"cache hit"
		));
		final var service = new NewsSummarizationService(summarizer, persistence, planner, APP_METRICS);

		final var result = service.summarize(sampleProcessedNews("id-4", "ru")).block();

		assertNotNull(result);
		assertEquals("Cached summary", result.getContentSummary());
		assertEquals("Cached summary", persistence.savedSummary);
		assertEquals("GEMINI:gemini-2.5-flash", persistence.savedModel);
		verifyNoInteractions(summarizer);
	}

	@Test
	void inheritSummaryCopiesRepresentativeSummary() {
		final var summarizer = new StubSummarizationService("unused");
		final var persistence = new StubPersistenceService();
		persistence.reusableSummary = new NewsPersistenceService.CachedSummary("GROQ:model", "ru", "Representative summary");
		final var planner = planner(NewsSummaryPlanner.SummaryPlan.useProvider());
		final var service = new NewsSummarizationService(summarizer, persistence, planner, APP_METRICS);

		final var result = service.inheritSummary(sampleProcessedNews("id-5", "ru"), "repr-1").block();

		assertNotNull(result);
		assertEquals("Representative summary", result.getContentSummary());
		assertEquals("Representative summary", persistence.savedSummary);
		assertEquals("GROQ:model", persistence.savedModel);
	}

	private static ProcessedNews sampleProcessedNews(final String id, final String language) {
		return ProcessedNews.builder()
			.id(id)
			.link("https://example.com/" + id)
			.title("Title " + id)
			.description("Description")
			.rawContent("Raw content " + id)
			.cleanContent("Clean content " + id)
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.fetchedDate(OffsetDateTime.parse("2026-02-01T12:01:00Z"))
			.contentHash("hash-" + id)
			.language(language)
			.build();
	}

	private static NewsSummaryPlanner planner(final NewsSummaryPlanner.SummaryPlan plan) {
		final NewsSummaryPlanner planner = mock(NewsSummaryPlanner.class);
		when(planner.planForRepresentative(any(), any(), anyString(), any(SummarizationUseCase.class)))
			.thenReturn(Mono.just(plan));
		return planner;
	}

	private static final class StubSummarizationService implements SummarizationService {

		private final String response;
		private String lastText;

		private StubSummarizationService(final String response) {
			this.response = response;
		}

		@Override
		public Mono<String> summarize(final String text, final String lang) {
			lastText = text;
			return Mono.just(response);
		}

		@Override
		public Mono<SummarizationResult> summarizeDetailed(
			final String text,
			final String lang,
			final SummarizationUseCase useCase
		) {
			lastText = text;
			return Mono.just(new SummarizationResult(response, "GEMINI:gemini-2.5-flash"));
		}
	}

	private static final class ErroringSummarizationService implements SummarizationService {

		private final RuntimeException error;

		private ErroringSummarizationService(final RuntimeException error) {
			this.error = error;
		}

		@Override
		public Mono<String> summarize(final String text, final String lang) {
			return Mono.error(error);
		}

		@Override
		public Mono<SummarizationResult> summarizeDetailed(
			final String text,
			final String lang,
			final SummarizationUseCase useCase
		) {
			return Mono.error(error);
		}
	}

	private static final class StubPersistenceService extends NewsPersistenceService {

		private final List<ProcessingStatus> statusHistory = new ArrayList<>();
		private String savedNewsId;
		private String savedLang;
		private String savedSummary;
		private String savedModel;
		private int savedSummaryCalls;
		private NewsPersistenceService.CachedSummary reusableSummary;

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
			savedModel = model;
			savedSummaryCalls++;
			return Mono.empty();
		}

		@Override
		public Mono<CachedSummary> findReusableSummary(final String newsId, final String contentHash, final String lang) {
			return reusableSummary == null ? Mono.empty() : Mono.just(reusableSummary);
		}
	}
}
