package com.nexus.press.app.news.model;

import java.time.OffsetDateTime;
import java.util.List;

public record NewsPipelineDebugReport(
	OffsetDateTime generatedAt,
	OffsetDateTime lookbackFrom,
	OffsetDateTime lookbackTo,
	PipelineRuntimeCounters counters,
	int totalRecentClusters,
	int matchedClusters,
	List<ClusterReportCluster> topClusters
) {
}
