package com.nexus.press.app.news.format;

import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.news.model.ClusterReportCluster;
import com.nexus.press.app.news.model.ClusterReportNewsItem;
import com.nexus.press.app.news.model.NewsPipelineDebugReport;
import com.nexus.press.app.news.model.PipelineRuntimeCounters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsPipelineDebugReportFormatterTest {

	@Test
	void toTelegramMessageRendersCountersAndClusters() {
		final var formatter = new NewsPipelineDebugReportFormatter();
		final var report = new NewsPipelineDebugReport(
			OffsetDateTime.parse("2026-03-23T10:00:00Z"),
			null,
			null,
			new PipelineRuntimeCounters(12, 9, 8),
			4,
			2,
			List.of(new ClusterReportCluster(
				"news-1",
				"Cluster representative",
				OffsetDateTime.parse("2026-03-23T09:55:00Z"),
				5,
				List.of(
					new ClusterReportNewsItem("news-1", "First", "https://example.com/1", "BBC", OffsetDateTime.parse("2026-03-23T09:55:00Z")),
					new ClusterReportNewsItem("news-2", "Second", "https://example.com/2", "REUTERS", OffsetDateTime.parse("2026-03-23T09:50:00Z"))
				)
			))
		);

		final String message = formatter.toTelegramMessage(report);

		assertTrue(message.contains("Новые новости: 12"));
		assertTrue(message.contains("Наполнено контентом: 9"));
		assertTrue(message.contains("Эмбеддинг DONE: 8"));
		assertTrue(message.contains("сейчас пошли бы в summary-stage"));
		assertTrue(message.contains("Кластеров размера 5-10: 2"));
		assertTrue(message.contains("Cluster representative"));
		assertTrue(message.contains("https://example.com/1"));
		assertTrue(message.contains("https://example.com/2"));
	}
}
