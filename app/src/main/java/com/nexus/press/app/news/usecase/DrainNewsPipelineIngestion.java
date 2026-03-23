package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.NewsPipelineBacklogSnapshot;
import com.nexus.press.app.news.model.NewsPipelineDrainResult;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.observability.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrainNewsPipelineIngestion {

	private final PostgresNewsPipelineQuery newsPipelineQuery;
	private final PopulateNewsContent populateNewsContent;
	private final EmbedNewsBatch embedNewsBatch;
	private final NewsPipelineProperties newsPipelineProperties;
	private final AppMetrics appMetrics;

	public Mono<NewsPipelineDrainResult> execute() {
		final var timerSample = appMetrics.startJobTimer();

		return processContentBatch()
			.flatMap(contentClaimed -> processEmbeddingBatch()
				.map(embeddingClaimed -> new NewsPipelineDrainResult(contentClaimed, embeddingClaimed, 0)))
			.flatMap(result -> refreshBacklogMetrics().thenReturn(result))
			.doOnSuccess(result -> {
				appMetrics.jobSuccess("news_pipeline_worker_ingestion", timerSample);
				if (result.contentClaimed() > 0 || result.embeddingClaimed() > 0) {
					log.info(
						"News pipeline ingestion drained: content={} embedding={}",
						result.contentClaimed(),
						result.embeddingClaimed()
					);
				}
			})
			.doOnError(error -> appMetrics.jobFailure("news_pipeline_worker_ingestion", timerSample, error));
	}

	private Mono<Long> processContentBatch() {
		return newsPipelineQuery.claimNewsPendingContent(
				newsPipelineProperties.getContentBatchSize(),
				newsPipelineProperties.getClaimTimeout()
			)
			.collectList()
			.flatMap(batch -> processClaimedContentBatch(batch).thenReturn((long) batch.size()));
	}

	private Mono<Long> processEmbeddingBatch() {
		final int batchSize = Math.max(1, newsPipelineProperties.getEmbeddingBatchSize());
		final int concurrency = Math.max(1, newsPipelineProperties.getEmbeddingConcurrency());
		return newsPipelineQuery.claimNewsPendingEmbedding(
				batchSize * concurrency,
				newsPipelineProperties.getClaimTimeout()
			)
			.collectList()
			.flatMap(batch -> processClaimedEmbeddingBatch(batch, batchSize, concurrency).thenReturn((long) batch.size()));
	}

	private Mono<Void> processClaimedContentBatch(final List<RawNews> batch) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(batch)
			.flatMap(populateNewsContent::execute, Math.max(1, newsPipelineProperties.getPopulateConcurrency()), 1)
			.then();
	}

	private Mono<Void> processClaimedEmbeddingBatch(final List<RawNews> batch, final int batchSize, final int concurrency) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(partition(batch, batchSize))
			.flatMap(embedNewsBatch::execute, concurrency, 1)
			.then();
	}

	private Mono<NewsPipelineBacklogSnapshot> refreshBacklogMetrics() {
		return newsPipelineQuery.loadPipelineBacklog()
			.doOnNext(this::recordBacklogMetrics);
	}

	private void recordBacklogMetrics(final NewsPipelineBacklogSnapshot snapshot) {
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

	private static <T> List<List<T>> partition(final List<T> items, final int batchSize) {
		final List<List<T>> batches = new java.util.ArrayList<>();
		for (int i = 0; i < items.size(); i += batchSize) {
			batches.add(items.subList(i, Math.min(items.size(), i + batchSize)));
		}
		return batches;
	}
}
