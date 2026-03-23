package com.nexus.press.app.news.integration;

import com.nexus.press.app.news.model.Media;
import com.nexus.press.app.news.model.RawNews;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlContentSupportTest {

	@Test
	void extractArticleTextPrefersCandidateThatMatchesDescriptionOverFeedNoise() {
		final RawNews news = RawNews.builder()
			.id("https://www.m24.ru/news/politika/11032026/881662")
			.link("https://www.m24.ru/news/politika/11032026/881662")
			.title("Совбез ООН потребовал от Ирана прекратить атаки")
			.description("Совет безопасности ООН одобрил резолюцию по Ближневосточному конфликту, которая требует от Ирана прекратить атаки на арабские страны.")
			.source(Media.M24)
			.build();
		final String html = """
			<html>
			  <body>
			    <main>
			      <p>Мэр Москвы. Сергей Собянин рассказал о развитии района Гольяново.</p>
			      <p>Лавров заявил, что РФ готова содействовать миру на Ближнем Востоке.</p>
			    </main>
			    <article class="article-body">
			      <p>Совет безопасности ООН одобрил резолюцию по Ближневосточному конфликту, которая требует от Ирана прекратить атаки на арабские страны. Об этом сообщает ТАСС.</p>
			      <p>Проект резолюции был подготовлен Бахрейном при поддержке стран Персидского залива.</p>
			      <p>В документе резко осуждаются атаки Ирана по территориям Бахрейна, Кувейта, Омана, Катара, Саудовской Аравии, ОАЭ и Иордании.</p>
			    </article>
			  </body>
			</html>
			""";

		final String extracted = HtmlContentSupport.extractArticleText(news, html, HtmlContentSupport.GENERIC_ARTICLE_SELECTORS);

		assertTrue(extracted.contains("Совет безопасности ООН одобрил резолюцию"));
		assertTrue(extracted.contains("Проект резолюции был подготовлен Бахрейном"));
		assertFalse(extracted.contains("Сергей Собянин рассказал"));
	}

	@Test
	void extractArticleTextUsesJsonLdArticleBodyWhenAvailable() {
		final RawNews news = RawNews.builder()
			.id("https://www.vedomosti.ru/politics/news/example")
			.link("https://www.vedomosti.ru/politics/news/example")
			.title("Совбез ООН принял резолюцию")
			.description("Совет Безопасности ООН принял резолюцию Бахрейна по Ближнему Востоку.")
			.source(Media.VEDOMOSTI)
			.build();
		final String html = """
			<html>
			  <head>
			    <script type="application/ld+json">
			      {
			        "@type": "NewsArticle",
			        "articleBody": "Совет Безопасности ООН принял резолюцию Бахрейна по Ближнему Востоку. В ходе голосования Россия и Китай воздержались. Иран ответил ударами по американским военным базам на Ближнем Востоке."
			      }
			    </script>
			  </head>
			  <body>
			    <main>
			      <p>Короткий анонс.</p>
			    </main>
			  </body>
			</html>
			""";

		final String extracted = HtmlContentSupport.extractArticleText(news, html, HtmlContentSupport.GENERIC_ARTICLE_SELECTORS);

		assertTrue(extracted.contains("Совет Безопасности ООН принял резолюцию Бахрейна"));
		assertTrue(extracted.contains("Иран ответил ударами"));
	}
}
