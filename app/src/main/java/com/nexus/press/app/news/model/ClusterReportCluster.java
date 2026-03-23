package com.nexus.press.app.news.model;

import java.time.OffsetDateTime;
import java.util.List;

public record ClusterReportCluster(
	String representativeId,
	String representativeTitle,
	OffsetDateTime representativeEventAt,
	int size,
	List<ClusterReportNewsItem> items
) {
}
