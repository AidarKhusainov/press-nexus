package com.nexus.press.app.service.news;

import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
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

	@Test
	void cleanCutsCommentTailByTimestampMarkers() {
		final String raw = """
			Продавец уверен: кому надо, тот заплатит. В Барнауле на одной из онлайн-площадок выставили на продажу необычную монету.
			Стоимость лота составляет 9 999 999 рублей.
			22:42:20 09-03-2026 Когда монета чеканится, у неё бьются обе стороны сразу.
			08:02:26 10-03-2026 Многие верят.
			""";

		final String cleaned = cleaner.clean("Редкий рубль", "Описание", raw);

		assertTrue(cleaned.contains("Стоимость лота составляет"));
		assertFalse(cleaned.contains("22:42:20 09-03-2026"));
		assertFalse(cleaned.contains("Многие верят"));
	}

	@Test
	void cleanCutsTelegramPromoAndSourceFooter() {
		final RawNews news = RawNews.builder()
			.id("https://yamal-media.ru/news/example")
			.link("https://yamal-media.ru/news/example")
			.title("Жители начали активно заказывать лакомства")
			.description("Короткое описание")
			.rawContent("""
				Жители России начали активно заказывать лакомства для домашних животных из ямальской оленины.

				Еще один магазин поставил на маркетплейсы больше 10 тысяч товаров за год.

				Самые важные и оперативные новости — в нашем телеграм-канале «Ямал-Медиа».
				""")
			.source(Media.YAMAL_MEDIA)
			.build();

		final String cleaned = cleaner.clean(news);

		assertTrue(cleaned.contains("Еще один магазин поставил"));
		assertFalse(cleaned.contains("телеграм-канале"));
	}
}
