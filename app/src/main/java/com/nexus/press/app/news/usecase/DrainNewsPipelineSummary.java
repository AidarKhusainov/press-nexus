package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.NewsPipelineBacklogSnapshot;
import com.nexus.press.app.news.model.NewsPipelineDrainResult;
import com.nexus.press.app.news.model.ProcessedNews;
import com.nexus.press.app.news.model.ProcessingStatus;
import com.nexus.press.app.news.model.RawNews;
import com.nexus.press.app.news.persistence.query.PostgresNewsPipelineQuery;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.ai.model.SummarizationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrainNewsPipelineSummary {

	private final PostgresNewsPipelineQuery newsPipelineQuery;
	private final NewsRepository newsRepository;
	private final FindNewsCluster findNewsCluster;
	private final SummarizeNews summarizeNews;
	private final InheritNewsSummary inheritNewsSummary;
	private final NewsPipelineProperties newsPipelineProperties;
	private final SimilarityProperties similarityProperties;
	private final AppMetrics appMetrics;

	public Mono<NewsPipelineDrainResult> execute() {
		final var timerSample = appMetrics.startJobTimer();

		return processSummaryBatch()
			.map(summaryClaimed -> new NewsPipelineDrainResult(0, 0, summaryClaimed))
			.flatMap(result -> refreshBacklogMetrics().thenReturn(result))
			.doOnSuccess(result -> {
				appMetrics.jobSuccess("news_pipeline_worker_summary", timerSample);
				if (result.summaryClaimed() > 0) {
					log.info("News pipeline summary drained: summary={}", result.summaryClaimed());
				}
			})
			.doOnError(error -> appMetrics.jobFailure("news_pipeline_worker_summary", timerSample, error));
	}

	private Mono<Long> processSummaryBatch() {
		return newsPipelineQuery.claimNewsPendingSummary(
				newsPipelineProperties.getSummaryBatchSize(),
				newsPipelineProperties.getClaimTimeout(),
				newsPipelineProperties.getSummaryMaturity()
			)
			.collectList()
			.flatMap(batch -> processClaimedSummaryBatch(batch).thenReturn((long) batch.size()));
	}

	private Mono<Void> processClaimedSummaryBatch(final List<RawNews> batch) {
		if (batch.isEmpty()) {
			return Mono.empty();
		}

		return Flux.fromIterable(batch)
			.map(this::toProcessedNews)
			.concatMap(this::classifySummaryCandidate)
			.collectList()
			.flatMap(assignments -> processRepresentatives(assignments)
				.then(processDuplicates(assignments)))
			.then();
	}

	private Mono<SummaryAssignment> classifySummaryCandidate(final ProcessedNews news) {
		return findNewsCluster.execute(news.getId(), similarityProperties.getClusterMinScore())
			.map(cluster -> new SummaryAssignment(news, cluster));
	}

	private Mono<Void> processRepresentatives(final List<SummaryAssignment> assignments) {
		final List<SummaryAssignment> representatives = assignments.stream()
			.filter(SummaryAssignment::isRepresentative)
			.toList();
		final int concurrency = Math.max(1, newsPipelineProperties.getSummaryConcurrency());
		return Flux.fromIterable(representatives)
			.flatMap(assignment -> summarizeNews.execute(
				assignment.news(),
				assignment.cluster(),
				SummarizationUseCase.AUTO_CLUSTER
			).onErrorResume(ex -> handleSummaryFailure(assignment.news(), ex)), concurrency, 1)
			.then();
	}

	private Mono<Void> processDuplicates(final List<SummaryAssignment> assignments) {
		final List<SummaryAssignment> duplicates = assignments.stream()
			.filter(assignment -> !assignment.isRepresentative())
			.toList();
		final int concurrency = Math.max(1, newsPipelineProperties.getSummaryConcurrency());
		return Flux.fromIterable(duplicates)
			.flatMap(assignment -> {
				log.info(
					"Пропускаем прямую суммаризацию не-репрезентативной новости: id={} representative={}",
					assignment.news().getId(),
					assignment.cluster().representativeId()
				);
				return inheritNewsSummary.execute(
					assignment.news(),
					assignment.cluster().representativeId()
				).onErrorResume(ex -> handleSummaryFailure(assignment.news(), ex));
			}, concurrency, 1)
			.then();
	}

	private Mono<ProcessedNews> handleSummaryFailure(final ProcessedNews news, final Throwable ex) {
		log.warn("Сбой summary-stage для новости id={}", news.getId(), ex);
		return newsRepository.updateStatusSummary(news.getId(), ProcessingStatus.FAILED)
			.then(Mono.empty());
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

	private ProcessedNews toProcessedNews(final RawNews news) {
		return ProcessedNews.builder()
			.id(news.getId())
			.link(news.getLink())
			.title(news.getTitle())
			.description(news.getDescription())
			.rawContent(news.getRawContent())
			.cleanContent(news.getCleanContent())
			.source(news.getSource())
			.publishedDate(news.getPublishedDate())
			.fetchedDate(news.getFetchedDate())
			.language(news.getLanguage())
			.build();
	}

	private record SummaryAssignment(
		ProcessedNews news,
		NewsCluster cluster
	) {

		private boolean isRepresentative() {
			return cluster.representativeId() != null && cluster.representativeId().equals(news.getId());
		}
	}
}
