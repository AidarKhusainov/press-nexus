package com.nexus.press.app.service.brief;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.brief.model.DailyBriefItem;
import org.springframework.stereotype.Component;

@Component
public class DailyBriefFormatter {

	private static final DateTimeFormatter HEADER_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
		.withLocale(Locale.forLanguageTag("ru"))
		.withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter ITEM_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
		.withLocale(Locale.forLanguageTag("ru"))
		.withZone(ZoneId.systemDefault());

	public String toTelegramMessage(final DailyBrief brief) {
		final StringBuilder sb = new StringBuilder();
		sb.append(toTelegramHeader(brief)).append("\n\n");

		if (brief.items().isEmpty()) {
			sb.append("За выбранный период подтвержденных новостей пока нет.");
			return sb.toString();
		}

		for (int i = 0; i < brief.items().size(); i++) {
			sb.append(toTelegramCard(brief.items().get(i), i + 1)).append("\n\n");
		}

		sb.append(toTelegramFooter());
		return sb.toString();
	}

	public String toTelegramHeader(final DailyBrief brief) {
		return "Press Nexus Lite\n" +
			"Сводка без перегруза за 7 минут\n" +
			"Обновлено: " + HEADER_TIME_FMT.format(brief.generatedAt());
	}

	public String toTelegramCard(final DailyBriefItem item, final int index) {
		final StringBuilder sb = new StringBuilder();
		sb.append(index).append(". ").append(item.title()).append("\n");
		sb.append("Важность: ").append(importanceLabel(item.importance())).append(" • ");
		if (item.eventAt() != null) sb.append(ITEM_TIME_FMT.format(item.eventAt())); else sb.append("время н/д");
		sb.append("\n");
		sb.append("Что случилось: ").append(item.whatHappened()).append("\n");
		sb.append("Почему важно: ").append(item.whyImportant()).append("\n");
		sb.append("Что дальше: ").append(item.whatNext()).append("\n");
		sb.append("Источник: ").append(item.media()).append("\n");
		sb.append(item.url());
		return sb.toString();
	}

	public String toTelegramFooter() {
		return "Формат: must know / good to know, нейтрально и без кликбейта.";
	}

	private String importanceLabel(final BriefImportance importance) {
		return importance == BriefImportance.MUST_KNOW ? "must know" : "good to know";
	}
}
