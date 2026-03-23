package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.nexus.press.app.news.model.CachedNewsSummary;
import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.ai.integration.summ.SummarizationService;
import com.nexus.press.app.ai.model.SummarizationResult;
import com.nexus.press.app.ai.model.SummarizationUseCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SummarizeNewsTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void summarizePersistsSummaryAndMarksStatusDone() {
		final var summarizer = new StubSummarizationService("Краткая выжимка по событию.");
		final var repository = new RecordingNewsRepository();
		final var planner = planner(PlanNewsSummary.SummaryPlan.useProvider());
		final var service = new SummarizeNews(summarizer, repository, planner, APP_METRICS);

		final var result = service.execute(
			sampleProcessedNews("id-1", "ru"),
			new NewsCluster(Set.of("id-1"), "id-1"),
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertNotNull(result);
		assertEquals("Краткая выжимка по событию.", result.getContentSummary());
		assertEquals(List.of(ProcessingStatus.IN_PROGRESS, ProcessingStatus.DONE), repository.statusHistory);
		assertEquals(1, repository.savedSummaryCalls);
		assertEquals("ru", repository.savedLang);
		assertEquals("Краткая выжимка по событию.", repository.savedSummary);
		assertEquals("Clean content id-1", summarizer.lastText);
	}

	@Test
	void summarizeFallsBackToTitleWhenModelReturnsBlankSummary() {
		final var summarizer = new StubSummarizationService("   ");
		final var repository = new RecordingNewsRepository();
		final var planner = planner(PlanNewsSummary.SummaryPlan.useProvider());
		final var service = new SummarizeNews(summarizer, repository, planner, APP_METRICS);

		final var result = service.execute(
			sampleProcessedNews("id-2", "ru"),
			new NewsCluster(Set.of("id-2"), "id-2"),
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertNotNull(result);
		assertEquals("Title id-2", result.getContentSummary());
		assertEquals("Title id-2", repository.savedSummary);
		assertEquals("ru", repository.savedLang);
		assertEquals("Clean content id-2", summarizer.lastText);
	}

	@Test
	void summarizeUsesFallbackWhenProviderFails() {
		final var summarizer = new ErroringSummarizationService(new RuntimeException("quota exhausted"));
		final var repository = new RecordingNewsRepository();
		final var planner = planner(PlanNewsSummary.SummaryPlan.useProvider());
		final var service = new SummarizeNews(summarizer, repository, planner, APP_METRICS);

		final var result = service.execute(
			sampleProcessedNews("id-3", "ru"),
			new NewsCluster(Set.of("id-3"), "id-3"),
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertNotNull(result);
		assertEquals("Title id-3. Clean content id-3", result.getContentSummary());
		assertEquals(List.of(ProcessingStatus.IN_PROGRESS, ProcessingStatus.DONE), repository.statusHistory);
		assertEquals(1, repository.savedSummaryCalls);
		assertEquals("FALLBACK:headline-lead-v1", repository.savedModel);
	}

	@Test
	void summarizeReusesCachedSummaryWithoutProviderCall() {
		final var summarizer = mock(SummarizationService.class);
		final var repository = new RecordingNewsRepository();
		final var planner = planner(PlanNewsSummary.SummaryPlan.reuseCached(
			new CachedNewsSummary("GEMINI:gemini-2.5-flash", "ru", "Cached summary"),
			"cache hit"
		));
		final var service = new SummarizeNews(summarizer, repository, planner, APP_METRICS);

		final var result = service.execute(
			sampleProcessedNews("id-4", "ru"),
			new NewsCluster(Set.of("id-4"), "id-4"),
			SummarizationUseCase.AUTO_CLUSTER
		).block();

		assertNotNull(result);
		assertEquals("Cached summary", result.getContentSummary());
		assertEquals("Cached summary", repository.savedSummary);
		assertEquals("GEMINI:gemini-2.5-flash", repository.savedModel);
		verifyNoInteractions(summarizer);
	}

	@Test
	void inheritSummaryCopiesRepresentativeSummary() {
		final var summarizer = new StubSummarizationService("unused");
		final var repository = new RecordingNewsRepository();
		repository.reusableSummary = new CachedNewsSummary("GROQ:model", "ru", "Representative summary");
		final var summarizeNews = new SummarizeNews(summarizer, repository, planner(PlanNewsSummary.SummaryPlan.useProvider()), APP_METRICS);
		final var service = new InheritNewsSummary(repository, summarizeNews, APP_METRICS);

		final var result = service.execute(sampleProcessedNews("id-5", "ru"), "repr-1").block();

		assertNotNull(result);
		assertEquals("Representative summary", result.getContentSummary());
		assertEquals("Representative summary", repository.savedSummary);
		assertEquals("GROQ:model", repository.savedModel);
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
			.language(language)
			.build();
	}

	private static PlanNewsSummary planner(final PlanNewsSummary.SummaryPlan plan) {
		final PlanNewsSummary planner = mock(PlanNewsSummary.class);
		when(planner.execute(any(), any(), anyString(), any(SummarizationUseCase.class)))
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

	private static final class RecordingNewsRepository implements NewsRepository {

		private final List<ProcessingStatus> statusHistory = new ArrayList<>();
		private String savedLang;
		private String savedSummary;
		private String savedModel;
		private int savedSummaryCalls;
		private CachedNewsSummary reusableSummary;

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
			savedLang = lang;
			savedSummary = summary;
			savedModel = model;
			savedSummaryCalls++;
			return Mono.empty();
		}

		@Override
		public Mono<CachedNewsSummary> findReusableSummary(final String newsId, final String lang) {
			return reusableSummary == null ? Mono.empty() : Mono.just(reusableSummary);
		}

		@Override
		public Mono<NewsEntity> upsertByUrl(final com.nexus.press.app.news.model.NewsUpsertRequest request, final String id, final OffsetDateTime fetchedAt) {
			return Mono.empty();
		}

		@Override
		public Mono<NewsEntity> upsertByExternalId(
			final com.nexus.press.app.news.model.NewsUpsertRequest request,
			final String id,
			final OffsetDateTime fetchedAt
		) {
			return Mono.empty();
		}

		@Override
		public Mono<NewsEntity> saveDiscoveredIfAbsent(
			final com.nexus.press.app.news.model.NewsUpsertRequest request,
			final String id,
			final OffsetDateTime fetchedAt
		) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> updateStatusContent(final String id, final ProcessingStatus status) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> updateStatusEmbedding(final String id, final ProcessingStatus status) {
			return Mono.empty();
		}

		@Override
		public Mono<NewsEntity> loadExistingByNaturalKeys(final String id, final String url) {
			return Mono.empty();
		}
	}
}
