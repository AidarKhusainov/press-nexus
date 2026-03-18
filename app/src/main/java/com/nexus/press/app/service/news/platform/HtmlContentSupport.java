package com.nexus.press.app.service.news.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.nexus.press.app.service.news.model.RawNews;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

final class HtmlContentSupport {

	static final int PRIMARY_TEXT_MIN_LEN = 280;
	static final int PARAGRAPH_FALLBACK_MIN_LEN = 120;
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final String GENERIC_NOISE_SELECTORS =
		"script,style,noscript,iframe,svg,header,footer,nav,aside,form," +
			"[class*=comment],[id*=comment],[class*=related],[id*=related],[class*=recommend],[id*=recommend]," +
			"[class*=cookie],[id*=cookie],[class*=subscribe],[id*=subscribe],[class*=share],[id*=share]," +
			"[class*=social],[id*=social],[class*=footer],[id*=footer],[class*=banner],[id*=banner]," +
			"[class*=sidebar],[id*=sidebar],[class*=widget],[id*=widget],[class*=promo],[id*=promo]," +
			"[class*=telegram],[id*=telegram]";
	private static final List<String> GENERIC_ARTICLE_ROOT_SELECTORS = List.of(
		"[itemprop=articleBody]",
		"article",
		".article-body",
		".story-body",
		".entry-content",
		".post-content",
		".article__content",
		".article__text",
		".news__content",
		".news__text",
		".content__body",
		".content__text",
		".b-material-wrapper__text",
		".textHolder",
		".articleText",
		"main"
	);

	static final List<String> GENERIC_ARTICLE_SELECTORS = List.of(
		"article p",
		"[itemprop=articleBody] p",
		".article-body p",
		".story-body p",
		".entry-content p",
		".post-content p"
	);

	private HtmlContentSupport() {}

	static String extractArticleText(final String html, final List<String> selectors) {
		if (!StringUtils.hasText(html)) return "";

		final var doc = Jsoup.parse(html);
		sanitize(doc);

		for (final var selector : selectors) {
			final var text = join(doc.select(selector));
			if (text.length() >= PRIMARY_TEXT_MIN_LEN) return text;
		}

		final var anyParagraphs = join(doc.select("p"));
		if (anyParagraphs.length() >= PARAGRAPH_FALLBACK_MIN_LEN) return anyParagraphs;

		final Element body = doc.body();
		return body == null ? "" : normalize(body.text());
	}

	static String extractArticleText(final RawNews news, final String html, final List<String> selectors) {
		if (!StringUtils.hasText(html)) return "";

		final var doc = Jsoup.parse(html);
		final String jsonLdArticle = extractJsonLdArticleBody(doc);
		sanitize(doc);
		final List<String> candidates = new ArrayList<>();
		if (StringUtils.hasText(jsonLdArticle)) {
			candidates.add(jsonLdArticle);
		}
		for (final var rootSelector : GENERIC_ARTICLE_ROOT_SELECTORS) {
			for (final var root : doc.select(rootSelector)) {
				final String candidate = extractRootText(root);
				if (StringUtils.hasText(candidate)) {
					candidates.add(candidate);
				}
			}
		}
		for (final var selector : selectors) {
			final String candidate = join(doc.select(selector));
			if (StringUtils.hasText(candidate)) {
				candidates.add(candidate);
			}
		}

		return candidates.stream()
			.map(HtmlContentSupport::normalize)
			.filter(StringUtils::hasText)
			.max(Comparator.comparingInt(candidate -> scoreCandidate(candidate, news)))
			.filter(candidate -> candidate.length() >= PARAGRAPH_FALLBACK_MIN_LEN)
			.orElseGet(() -> extractArticleText(html, selectors));
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

	private static void sanitize(final Document doc) {
		doc.select(GENERIC_NOISE_SELECTORS).remove();
	}

	private static String extractRootText(final Element root) {
		final var clone = root.clone();
		clone.select(GENERIC_NOISE_SELECTORS).remove();
		final String paragraphText = join(clone.select("p"));
		if (paragraphText.length() >= PARAGRAPH_FALLBACK_MIN_LEN) {
			return paragraphText;
		}
		return normalize(clone.text());
	}

	private static int scoreCandidate(final String candidate, final RawNews news) {
		if (!StringUtils.hasText(candidate)) {
			return Integer.MIN_VALUE;
		}

		int score = Math.min(candidate.length(), 8_000);
		final String normalizedCandidate = normalize(candidate).toLowerCase();
		if (normalizedCandidate.contains("выскажись!") || normalizedCandidate.contains("читайте также")) {
			score -= 1_200;
		}
		if (normalizedCandidate.contains("данный сайт использует файлы cookies")
			|| normalizedCandidate.contains("правила комментирования материалов")) {
			score -= 1_800;
		}

		if (news != null) {
			final String title = normalize(news.getTitle()).toLowerCase();
			final String description = normalize(news.getDescription()).toLowerCase();
			if (StringUtils.hasText(title) && normalizedCandidate.contains(title)) {
				score += 1_500;
			}
			if (StringUtils.hasText(description)) {
				final String probe = description.substring(0, Math.min(description.length(), 120));
				if (probe.length() >= 32 && normalizedCandidate.contains(probe)) {
					score += 2_500;
				}
			}
		}
		return score;
	}

	private static String extractJsonLdArticleBody(final Document doc) {
		String best = "";
		for (final var script : doc.select("script[type=application/ld+json]")) {
			final String json = script.data();
			if (!StringUtils.hasText(json)) {
				continue;
			}
			try {
				final String candidate = longestArticleText(OBJECT_MAPPER.readTree(json));
				if (candidate.length() > best.length()) {
					best = candidate;
				}
			} catch (final Exception ignored) {}
		}
		return normalize(best);
	}

	private static String longestArticleText(final JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		if (node.isArray()) {
			String best = "";
			for (final var child : node) {
				final String candidate = longestArticleText(child);
				if (candidate.length() > best.length()) {
					best = candidate;
				}
			}
			return best;
		}
		if (!node.isObject()) {
			return "";
		}

		String best = "";
		for (final String field : List.of("articleBody", "text")) {
			final JsonNode value = node.get(field);
			if (value != null && value.isTextual()) {
				final String candidate = normalize(value.asText());
				if (candidate.length() > best.length()) {
					best = candidate;
				}
			}
		}
		if (best.length() >= PRIMARY_TEXT_MIN_LEN) {
			return best;
		}

		for (final var fields = node.fields(); fields.hasNext();) {
			final var entry = fields.next();
			final String candidate = longestArticleText(entry.getValue());
			if (candidate.length() > best.length()) {
				best = candidate;
			}
		}
		return best;
	}
}
