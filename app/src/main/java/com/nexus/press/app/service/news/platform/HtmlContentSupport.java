package com.nexus.press.app.service.news.platform;

import java.util.List;
import com.nexus.press.app.service.news.model.RawNews;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

final class HtmlContentSupport {

	static final int PRIMARY_TEXT_MIN_LEN = 280;
	static final int PARAGRAPH_FALLBACK_MIN_LEN = 120;

	static final List<String> GENERIC_ARTICLE_SELECTORS = List.of(
		"article p",
		"[itemprop=articleBody] p",
		".article-body p",
		".story-body p",
		".entry-content p",
		".post-content p",
		"main p"
	);

	private HtmlContentSupport() {}

	static String extractArticleText(final String html, final List<String> selectors) {
		if (!StringUtils.hasText(html)) return "";

		final var doc = Jsoup.parse(html);
		doc.select("script,style,noscript,iframe,svg,header,footer,nav,aside,form").remove();

		for (final var selector : selectors) {
			final var text = join(doc.select(selector));
			if (text.length() >= PRIMARY_TEXT_MIN_LEN) return text;
		}

		final var anyParagraphs = join(doc.select("p"));
		if (anyParagraphs.length() >= PARAGRAPH_FALLBACK_MIN_LEN) return anyParagraphs;

		final Element body = doc.body();
		return body == null ? "" : normalize(body.text());
	}

	static String fallbackFromDescription(final RawNews news) {
		final var title = normalize(news.getTitle());
		final var description = normalize(news.getDescription() == null ? "" : Jsoup.parse(news.getDescription()).text());
		if (StringUtils.hasText(title) && StringUtils.hasText(description)) return title + "\n\n" + description;
		if (StringUtils.hasText(title)) return title;
		return description;
	}

	static String normalize(final String value) {
		if (!StringUtils.hasText(value)) return "";
		return value.replace('\u00A0', ' ')
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.replaceAll("\n{3,}", "\n\n")
			.strip();
	}

	static void applyBrowserHeaders(final HttpHeaders headers) {
		headers.set(HttpHeaders.USER_AGENT,
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
				+ "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
		headers.set(HttpHeaders.ACCEPT,
			"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
	}

	private static String join(final Elements paragraphs) {
		final var chunks = paragraphs.stream()
			.map(el -> {
				el.select("br").append("\\n");
				return el.text().replace("\\n", "\n");
			})
			.map(HtmlContentSupport::normalize)
			.filter(StringUtils::hasText)
			.toList();
		return String.join("\n\n", chunks);
	}
}
