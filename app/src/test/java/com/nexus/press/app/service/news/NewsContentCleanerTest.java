package com.nexus.press.app.service.news;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsContentCleanerTest {

	private final NewsContentCleaner cleaner = new NewsContentCleaner();

	@Test
	void cleanRemovesReadMoreTail() {
		final String raw = """
			Lead paragraph with actual article text and enough detail to remain after cleaning.

			Second paragraph adds context and keeps the narrative coherent for embeddings.

			Читайте также

			Посторонний блок
			""";

		final String cleaned = cleaner.clean("Title", "Fallback", raw);

		assertFalse(cleaned.contains("Читайте также"));
		assertFalse(cleaned.contains("Посторонний блок"));
		assertTrue(cleaned.contains("Lead paragraph"));
		assertTrue(cleaned.contains("Second paragraph"));
	}

	@Test
	void cleanFallsBackToDescriptionWhenOnlyCommentRulesRemain() {
		final String raw = """
			Эти несложные правила помогут Вам получать удовольствие от общения на нашем сайте!

			Сообщение не должно содержать более 2500 знаков (с пробелами)

			В комментариях запрещаются выражения, содержащие ненормативную лексику.

			Администрация сайта оставляет за собой право удалять комментарии.
			""";

		final String cleaned = cleaner.clean("Полезный заголовок", "Короткое описание из RSS", raw);

		assertEquals("Полезный заголовок\n\nКороткое описание из RSS", cleaned);
	}

	@Test
	void cleanTrimsMetadataPrefixAndFeedbackTail() {
		final String title = "ПВО Саудовской Аравии сбила три ракеты";
		final String raw = """
			Регистрация пройдена успешно! Пожалуйста, перейдите по ссылке из письма. Политика В мире Экономика
			23:37 11.03.2026 https://ria.ru/20260311/araviya-2080081130.html
			ПВО Саудовской Аравии сбила три ракеты ПВО Саудовской Аравии сбила три ракеты.

			Минобороны Саудовской Аравии сообщило о перехвате и уничтожении беспилотника в небе над Восточной провинцией.

			Обратная связь Чтобы воспользоваться формой обратной связи, Вы должны войти на сайт.
			""";

		final String cleaned = cleaner.clean(title, "Описание", raw);

		assertTrue(cleaned.startsWith("Минобороны Саудовской Аравии"));
		assertFalse(cleaned.contains("Регистрация пройдена успешно"));
		assertFalse(cleaned.contains("Обратная связь"));
	}
}
