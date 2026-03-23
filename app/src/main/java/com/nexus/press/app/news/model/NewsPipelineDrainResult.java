package com.nexus.press.app.news.model;

public record NewsPipelineDrainResult(
	long contentClaimed,
	long embeddingClaimed,
	long summaryClaimed
) {

	public long totalClaimed() {
		return contentClaimed + embeddingClaimed + summaryClaimed;
	}
}
