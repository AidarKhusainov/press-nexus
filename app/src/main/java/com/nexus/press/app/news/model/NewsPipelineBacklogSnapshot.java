package com.nexus.press.app.news.model;

public record NewsPipelineBacklogSnapshot(
	long contentPending,
	long contentInProgress,
	long contentFailed,
	long embeddingPending,
	long embeddingInProgress,
	long embeddingFailed,
	long summaryPending,
	long summaryInProgress,
	long summaryFailed
) {

	public long totalOutstanding() {
		return contentPending + contentInProgress
			+ embeddingPending + embeddingInProgress
			+ summaryPending + summaryInProgress;
	}
}
