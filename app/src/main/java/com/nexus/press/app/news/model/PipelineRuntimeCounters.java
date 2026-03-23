package com.nexus.press.app.news.model;

public record PipelineRuntimeCounters(
	long discoveredNews,
	long populatedNews,
	long embeddedNews
) {
}
