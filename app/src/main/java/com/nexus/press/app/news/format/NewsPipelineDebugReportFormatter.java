package com.nexus.press.app.news.format;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.nexus.press.app.news.model.ClusterReportCluster;
import com.nexus.press.app.news.model.ClusterReportNewsItem;
import com.nexus.press.app.news.model.NewsPipelineDebugReport;
import org.springframework.stereotype.Component;

@Component
public class NewsPipelineDebugReportFormatter {

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm")
		.withLocale(Locale.forLanguageTag("ru"))
		.withZone(ZoneId.systemDefault());

	public String toTelegramMessage(final NewsPipelineDebugReport report) {
		final StringBuilder sb = new StringBuilder();
		sb.append("Press Nexus Pipeline Debug\n");
		sb.append("Сформировано: ").append(format(report.generatedAt())).append("\n");
		sb.append("Срез: кандидаты, которые сейчас пошли бы в summary-stage\n");
		sb.append("Summary worker: отключен\n\n");

		sb.append("За интервал с прошлого отчета:\n");
		sb.append("Новые новости: ").append(report.counters().discoveredNews()).append("\n");
		sb.append("Наполнено контентом: ").append(report.counters().populatedNews()).append("\n");
		sb.append("Эмбеддинг DONE: ").append(report.counters().embeddedNews()).append("\n");
		sb.append("Всего summary-ready кластеров: ").append(report.totalRecentClusters()).append("\n");
		sb.append("Кластеров размера 5-10: ").append(report.matchedClusters()).append("\n");

		if (report.topClusters().isEmpty()) {
			sb.append("\nПодходящих кластеров для ручного анализа пока нет.");
			return sb.toString();
		}

		sb.append("\nТоп кластеров для ручного анализа:\n\n");
		for (int index = 0; index < report.topClusters().size(); index++) {
			sb.append(formatCluster(index + 1, report.topClusters().get(index))).append("\n\n");
		}
		return sb.toString().strip();
	}

	private String formatCluster(final int index, final ClusterReportCluster cluster) {
		final StringBuilder sb = new StringBuilder();
		sb.append(index)
			.append(". Кластер ")
			.append(cluster.representativeId())
			.append(" • ")
			.append(cluster.size())
			.append(" новостей\n");
		sb.append("Репрезентативная: ").append(safe(cluster.representativeTitle())).append("\n");
		sb.append("Время: ").append(format(cluster.representativeEventAt())).append("\n");
		for (final ClusterReportNewsItem item : cluster.items()) {
			sb.append("- ")
				.append(format(item.eventAt()))
				.append(" • ")
				.append(safe(item.media()))
				.append(" • ")
				.append(safe(item.title()))
				.append("\n");
			sb.append(item.url()).append("\n");
		}
		return sb.toString().strip();
	}

	private String format(final java.time.OffsetDateTime value) {
		if (value == null) {
			return "н/д";
		}
		return DATE_TIME_FORMATTER.format(value);
	}

	private String safe(final String value) {
		if (value == null || value.isBlank()) {
			return "н/д";
		}
		return value.strip();
	}
}
