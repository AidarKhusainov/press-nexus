package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.ProcessedNews;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPipelineWorkerService {

	public record DrainResult(long contentClaimed, long embeddingClaimed, long summaryClaimed) {

		public long totalClaimed() {
			return contentClaimed + embeddingClaimed + summaryClaimed;
		}
	}

	private final NewsPersistenceService newsPersistenceService;
	private final NewsPopulateContentService newsPopulateContentService;
	private final NewsEmbeddingService newsEmbeddingService;
	private final NewsSummarizationService newsSummarizationService;
	private final NewsClusteringService newsClusteringService;
	private final NewsPipelineProperties newsPipelineProperties;
	private final SimilarityProperties similarityProperties;
	private final AppMetrics appMetrics;

	public Mono<DrainResult> drainOnce() {
		final var timerSample = appMetrics.startJobTimer();

		return processContentBatch()
			.flatMap(contentClaimed -> processEmbeddingBatch()
				.flatMap(embeddingClaimed -> processSummaryBatch()
					.map(summaryClaimed -> new DrainResult(contentClaimed, embeddingClaimed, summaryClaimed))))
			.flatMap(result -> refreshBacklogMetrics().thenReturn(result))
			.doOnSuccess(result -> {
				appMetrics.jobSuccess("news_pipeline_worker", timerSample);
				if (result.totalClaimed() > 0) {
					log.info(
						"News pipeline worker drained: content={} embedding={} summary={}",
						result.contentClaimed(),
						result.embeddingClaimed(),
						result.summaryClaimed()
					);
				}
			})
			.doOnError(error -> appMetrics.jobFailure("news_pipeline_worker", timerSample, error));
	}

	public Mono<NewsPersistenceService.PipelineBacklogSnapshot> refreshBacklogMetrics() {
		return newsPersistenceService.loadPipelineBacklog()
			.doOnNext(this::recordBacklogMetrics);
	}

	private Mono<Long> processContentBatch() {
		return newsPersistenceService.claimNewsPendingContent(
				newsPipelineProperties.getContentBatchSize(),
				newsPipelineProperties.getClaimTimeout()
			)
			.collectList()
			.flatMap(batch -> processClaimedContentBatch(batch).thenReturn((long) batch.size()));
	}

	private Mono<Long> processEmbeddingBatch() {
		final int batchSize = Math.max(1, newsPipelineProperties.getEmbeddingBatchSize());
		final int concurrency = Math.max(1, newsPipelineProperties.getEmbeddingConcurrency());
		return newsPersistenceService.claimNewsPendingEmbedding(
				batchSize * concurrency,
				newsPipelineProperties.getClaimTimeout()
			)
			.collectList()
			.flatMap(batch -> processClaimedEmbeddingBatch(batch, batchSize, concurrency).thenReturn((long) batch.size()));
	}

	private Mono<Long> processSummaryBatch() {
		return newsPersistenceService.claimNewsPendingSummary(
				newsPipelineProperties.getSummaryBatchSize(),
				newsPipelineProperties.getClaimTimeout()
			)
			.collectList()
			.flatMap(batch -> processClaimedSummaryBatch(batch).thenReturn((long) batch.size()));
	}

	private Mono<Void> processClaimedContentBatch(final List<RawNews> batch) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(batch)
			.flatMap(newsPopulateContentService::populate, Math.max(1, newsPipelineProperties.getPopulateConcurrency()), 1)
			.then();
	}

	private Mono<Void> processClaimedEmbeddingBatch(final List<RawNews> batch, final int batchSize, final int concurrency) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(partition(batch, batchSize))
			.flatMap(newsEmbeddingService::embedBatch, concurrency, 1)
			.then();
	}

	private Mono<Void> processClaimedSummaryBatch(final List<RawNews> batch) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(batch)
			.map(this::toProcessedNews)
			.flatMap(this::summarizeIfRepresentative, Math.max(1, newsPipelineProperties.getSummaryConcurrency()), 1)
			.then();
	}

	private Mono<ProcessedNews> summarizeIfRepresentative(final ProcessedNews news) {
		return newsClusteringService.clusterOf(news.getId(), similarityProperties.getClusterMinScore())
			.flatMap(cluster -> {
				final var representativeId = cluster.representativeId();
				if (representativeId == null || !representativeId.equals(news.getId())) {
					log.info("Пропускаем суммаризацию не-репрезентативной новости: id={} representative={}", news.getId(), representativeId);
					return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.DONE)
						.thenReturn(news);
				}
				return newsSummarizationService.summarize(news);
			})
			.onErrorResume(ex -> {
				log.warn("Сбой summary-stage для новости id={}", news.getId(), ex);
				return newsPersistenceService.updateStatusSummary(news.getId(), ProcessingStatus.FAILED)
					.then(Mono.empty());
			});
	}

	private static <T> List<List<T>> partition(final List<T> items, final int batchSize) {
		final List<List<T>> batches = new java.util.ArrayList<>();
		for (int i = 0; i < items.size(); i += batchSize) {
			batches.add(items.subList(i, Math.min(items.size(), i + batchSize)));
		}
		return batches;
	}

	private ProcessedNews toProcessedNews(final RawNews news) {
		return ProcessedNews.builder()
			.id(news.getId())
			.link(news.getLink())
			.title(news.getTitle())
			.description(news.getDescription())
			.rawContent(news.getRawContent())
			.source(news.getSource())
			.publishedDate(news.getPublishedDate())
			.language(news.getLanguage())
			.build();
	}

	private void recordBacklogMetrics(final NewsPersistenceService.PipelineBacklogSnapshot snapshot) {
		appMetrics.updatePipelineBacklog("content", "pending", snapshot.contentPending());
		appMetrics.updatePipelineBacklog("content", "in_progress", snapshot.contentInProgress());
		appMetrics.updatePipelineBacklog("content", "failed", snapshot.contentFailed());
		appMetrics.updatePipelineBacklog("embedding", "pending", snapshot.embeddingPending());
		appMetrics.updatePipelineBacklog("embedding", "in_progress", snapshot.embeddingInProgress());
		appMetrics.updatePipelineBacklog("embedding", "failed", snapshot.embeddingFailed());
		appMetrics.updatePipelineBacklog("summary", "pending", snapshot.summaryPending());
		appMetrics.updatePipelineBacklog("summary", "in_progress", snapshot.summaryInProgress());
		appMetrics.updatePipelineBacklog("summary", "failed", snapshot.summaryFailed());
	}
}
