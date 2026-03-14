package com.nexus.press.app.service.news;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.config.property.SummarizationProperties;
import com.nexus.press.app.service.ai.summ.SummarizationUseCase;
import com.nexus.press.app.service.news.model.ProcessedNews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSummaryPlanner {

	private static final Duration TOP_CANDIDATES_REFRESH = Duration.ofMinutes(5);
	private static final String REASON_CACHE_HIT = "cache hit";
	private static final String REASON_NOT_TOP_CLUSTER = "outside auto-cluster topN";
	private static final String REASON_BUDGET_EXHAUSTED = "budget exhausted";
	private static final String REASON_CLUSTER_IMMATURE = "cluster not mature";

	private final NewsPersistenceService newsPersistenceService;
	private final NewsClusteringService newsClusteringService;
	private final SimilarityProperties similarityProperties;
	private final SummarizationProperties summarizationProperties;

	private final AtomicReference<LocalDate> budgetDayUtc = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));
	private final Map<SummarizationUseCase, AtomicInteger> usedBudgetByUseCase = new EnumMap<>(SummarizationUseCase.class);
	private final AtomicReference<TopCandidatesCache> topCandidatesCache = new AtomicReference<>(
		new TopCandidatesCache(LocalDate.MIN, Instant.EPOCH, Set.of())
	);

	public Mono<SummaryPlan> planForRepresentative(
		final ProcessedNews news,
		final NewsClusteringService.Cluster cluster,
		final String lang,
		final SummarizationUseCase useCase
	) {
		return newsPersistenceService.findReusableSummary(news.getId(), news.getContentHash(), lang)
			.flatMap(summary -> Mono.just(SummaryPlan.reuseCached(summary, REASON_CACHE_HIT)))
			.switchIfEmpty(Mono.defer(() -> {
				if (useCase == SummarizationUseCase.AUTO_CLUSTER && !isMature(news)) {
					return Mono.just(SummaryPlan.defer(REASON_CLUSTER_IMMATURE));
				}
				return isEligibleForProvider(news, cluster, useCase)
					.flatMap(eligible -> {
						if (!eligible) {
							return Mono.just(SummaryPlan.useFallback(REASON_NOT_TOP_CLUSTER));
						}
						if (!reserveBudget(useCase)) {
							return Mono.just(SummaryPlan.useFallback(REASON_BUDGET_EXHAUSTED));
						}
						return Mono.just(SummaryPlan.useProvider());
					});
			}));
	}

	private Mono<Boolean> isEligibleForProvider(
		final ProcessedNews news,
		final NewsClusteringService.Cluster cluster,
		final SummarizationUseCase useCase
	) {
		if (useCase != SummarizationUseCase.AUTO_CLUSTER) {
			return Mono.just(true);
		}
		return loadTopRepresentativeIds()
			.map(topIds -> topIds.contains(news.getId()) || cluster.ids().size() > 1 && topIds.contains(cluster.representativeId()));
	}

	private Mono<Set<String>> loadTopRepresentativeIds() {
		final LocalDate today = LocalDate.now(ZoneOffset.UTC);
		final TopCandidatesCache cache = topCandidatesCache.get();
		if (cache.dayUtc().equals(today) && cache.loadedAt().plus(TOP_CANDIDATES_REFRESH).isAfter(Instant.now())) {
			return Mono.just(cache.topRepresentativeIds());
		}

		final OffsetDateTime from = today.atStartOfDay().atOffset(ZoneOffset.UTC);
		return Mono.zip(
				newsPersistenceService.loadSummaryPriorityCandidates(from).collectList(),
				newsClusteringService.buildClusters(similarityProperties.getClusterMinScore())
			)
			.map(tuple -> {
				final Map<String, NewsPersistenceService.SummaryPriorityCandidate> candidateById = tuple.getT1().stream()
					.collect(java.util.stream.Collectors.toMap(
						NewsPersistenceService.SummaryPriorityCandidate::newsId,
						candidate -> candidate
					));
				final List<ScoredRepresentative> ranked = tuple.getT2().stream()
					.map(cluster -> toScoredRepresentative(cluster, candidateById))
					.filter(java.util.Objects::nonNull)
					.sorted(Comparator.comparingDouble(ScoredRepresentative::score).reversed())
					.limit(summarizationProperties.autoClusterTopNPerDay())
					.toList();
				final Set<String> topIds = ranked.stream()
					.map(ScoredRepresentative::representativeId)
					.collect(java.util.stream.Collectors.toSet());
				topCandidatesCache.set(new TopCandidatesCache(today, Instant.now(), topIds));
				return topIds;
			});
	}

	private ScoredRepresentative toScoredRepresentative(
		final NewsClusteringService.Cluster cluster,
		final Map<String, NewsPersistenceService.SummaryPriorityCandidate> candidateById
	) {
		final NewsPersistenceService.SummaryPriorityCandidate candidate = candidateById.get(cluster.representativeId());
		if (candidate == null) {
			return null;
		}
		return new ScoredRepresentative(
			cluster.representativeId(),
			score(cluster.ids().size(), candidate.eventAt(), candidate.media())
		);
	}

	private double score(final int clusterSize, final OffsetDateTime eventAt, final String media) {
		final long ageMinutes = eventAt == null
			? 24L * 60L
			: Math.max(0L, ChronoUnit.MINUTES.between(eventAt, OffsetDateTime.now()));
		final double freshnessScore = Math.max(0d, 360d - Math.min(ageMinutes, 360L));
		final double sourceScore = isMajorMedia(media) ? 50d : 0d;
		return clusterSize * 1_000d + freshnessScore + sourceScore;
	}

	private boolean isMajorMedia(final String media) {
		if (media == null) {
			return false;
		}
		return switch (media.strip().toUpperCase()) {
			case "BBC", "CNN", "REUTERS", "NYTIMES", "DW",
				"RIA", "TASS", "RBK", "KOMMERSANT", "VEDOMOSTI" -> true;
			default -> false;
		};
	}

	private boolean isMature(final ProcessedNews news) {
		final OffsetDateTime eventAt = news.getPublishedDate() != null ? news.getPublishedDate() : news.getFetchedDate();
		if (eventAt == null) {
			return true;
		}
		return !eventAt.isAfter(OffsetDateTime.now().minus(summarizationProperties.autoClusterMaturity()));
	}

	private boolean reserveBudget(final SummarizationUseCase useCase) {
		synchronized (usedBudgetByUseCase) {
			final LocalDate today = LocalDate.now(ZoneOffset.UTC);
			if (!today.equals(budgetDayUtc.get())) {
				budgetDayUtc.set(today);
				usedBudgetByUseCase.clear();
			}
			final AtomicInteger counter = usedBudgetByUseCase.computeIfAbsent(useCase, ignored -> new AtomicInteger());
			if (counter.get() >= budgetLimit(useCase)) {
				return false;
			}
			counter.incrementAndGet();
			return true;
		}
	}

	private int budgetLimit(final SummarizationUseCase useCase) {
		return switch (useCase) {
			case AUTO_CLUSTER -> summarizationProperties.autoClusterDailyBudget();
			case USER_FACING -> summarizationProperties.userFacingDailyBudget();
			case ON_DEMAND -> summarizationProperties.reserveDailyBudget();
		};
	}

	private record TopCandidatesCache(
		LocalDate dayUtc,
		Instant loadedAt,
		Set<String> topRepresentativeIds
	) {
	}

	private record ScoredRepresentative(
		String representativeId,
		double score
	) {
	}

	public record SummaryPlan(
		PlanType type,
		String reason,
		NewsPersistenceService.CachedSummary cachedSummary
	) {

		static SummaryPlan reuseCached(final NewsPersistenceService.CachedSummary cachedSummary, final String reason) {
			return new SummaryPlan(PlanType.REUSE_CACHED, reason, cachedSummary);
		}

		static SummaryPlan useProvider() {
			return new SummaryPlan(PlanType.USE_PROVIDER, null, null);
		}

		static SummaryPlan useFallback(final String reason) {
			return new SummaryPlan(PlanType.USE_FALLBACK, reason, null);
		}

		static SummaryPlan defer(final String reason) {
			return new SummaryPlan(PlanType.DEFER, reason, null);
		}
	}

	public enum PlanType {
		REUSE_CACHED,
		USE_PROVIDER,
		USE_FALLBACK,
		DEFER
	}
}
