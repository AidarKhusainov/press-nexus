package com.nexus.press.app.news.model;

import java.time.OffsetDateTime;

public record ClusterReportNewsItem(
	String id,
	String title,
	String url,
	String media,
	OffsetDateTime eventAt
) {
}
