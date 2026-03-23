package com.nexus.press.app.news.model;

import java.time.OffsetDateTime;

public record NewsSummaryPriorityCandidate(
	String newsId,
	String media,
	OffsetDateTime eventAt
) {}
