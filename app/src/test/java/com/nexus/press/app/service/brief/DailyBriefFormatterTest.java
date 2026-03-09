package com.nexus.press.app.service.brief;

import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.brief.model.DailyBriefItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyBriefFormatterTest {

	@Test
	void toTelegramMessageRendersAllBriefSections() {
		final var formatter = new DailyBriefFormatter();
		final var brief = new DailyBrief(
			OffsetDateTime.parse("2026-03-07T08:00:00Z"),
			OffsetDateTime.parse("2026-03-06T08:00:00Z"),
			OffsetDateTime.parse("2026-03-07T08:00:00Z"),
			"ru",
			List.of(
				new DailyBriefItem(
					"news-1",
					"Важная новость",
					"https://example.com/news-1",
					"BBC",
					OffsetDateTime.parse("2026-03-07T07:30:00Z"),
					BriefImportance.MUST_KNOW,
					"Что случилось: кратко.",
					"Почему важно: контекст.",
					"Что дальше: ожидаются обновления."
				)
			)
		);

		final String message = formatter.toTelegramMessage(brief);

		assertTrue(message.contains("Press Nexus Lite"));
		assertTrue(message.contains("1. Важная новость"));
		assertTrue(message.contains("Важность: must know"));
		assertTrue(message.contains("Что случилось: Что случилось: кратко."));
		assertTrue(message.contains("Почему важно: Почему важно: контекст."));
		assertTrue(message.contains("Что дальше: Что дальше: ожидаются обновления."));
		assertTrue(message.contains("https://example.com/news-1"));
	}
}
