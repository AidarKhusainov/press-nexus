package com.nexus.press.app.service.news;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NewsContentCleaner {

	private static final int MIN_CLEAN_CONTENT_LENGTH = 80;
	private static final int PREFIX_SCAN_LIMIT = 2500;
	private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
	private static final Pattern MULTI_BREAK = Pattern.compile("(?:\\R\\s*){2,}");
	private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
	private static final Pattern TIME_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
	private static final Pattern COMMENT_TIMESTAMP_PATTERN = Pattern.compile("\\b\\d{2}:\\d{2}:\\d{2}\\s+\\d{2}-\\d{2}-\\d{4}\\b");
	private static final Pattern TUVA_DATES_PATTERN = Pattern.compile("\\b\\d+\\)\\s+\\d{2}\\.\\d{2}\\.\\d{4}:");

	private static final List<String> HARD_STOP_MARKERS = List.of(
		"читайте также",
		"related article",
		"related gallery",
		"sign up for",
		"обсуждения",
		"обратная связь",
		"вход на сайт",
		"восстановление пароля",
		"чтобы участвовать в дискуссии",
		"правила для комментариев",
		"правила комментирования материалов",
		"ранее мы писали"
	);

	private static final List<String> DISCARD_MARKERS = List.of(
		"регистрация пройдена успешно",
		"пожалуйста, перейдите по ссылке из письма",
		"отправить еще раз",
		"показать тут шапка",
		"эти несложные правила помогут вам получать удовольствие от общения на нашем сайте",
		"сообщение не должно содержать более 2500 знаков",
		"в комментариях запрещаются",
		"давайте будем уважать друг друга и сайт",
		"администрация сайта оставляет за собой право удалять комментарии",
		"scan the qr code",
		"editor’s note:",
		"editor's note:",
		"the views expressed in this commentary",
		"the content is produced solely by the conversation",
		"our live coverage for the day has ended",
		"выскажись!",
		"перейти в фотобанк",
		"подробности на сайте",
		"показать еще"
	);

	private static final List<String> INLINE_HARD_STOP_MARKERS = List.of(
		"подробнее: https://",
		"подробнее: http://",
		"все важные новости",
		"самые важные и оперативные новости",
		"данный сайт использует файлы cookies",
		"учредитель и главный редактор",
		"использование материалов допускается только",
		"правил комментирования материалов",
		"правила комментирования материалов",
		"ранее мы писали:"
	);

	public String clean(final RawNews news) {
		if (news == null) {
			return "";
		}
		return clean(news.getTitle(), news.getDescription(), news.getRawContent(), news.getSource());
	}

	public String clean(final String title, final String description, final String rawContent) {
		return clean(title, description, rawContent, null);
	}

	private String clean(
		final String title,
		final String description,
		final String rawContent,
		final Media media
	) {
		final String normalizedRaw = normalize(rawContent);
		if (!StringUtils.hasText(normalizedRaw)) {
			return fallback(title, description);
		}

		String candidate = stripInlineFragments(trimMetadataPrefix(normalizedRaw, normalize(title)));
		candidate = trimHardStopTail(candidate, media);
		final List<String> paragraphs = splitParagraphs(candidate);
		final List<String> kept = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		boolean textMarkerSeen = false;

		for (final String paragraph : paragraphs) {
			final String normalizedParagraph = normalize(paragraph);
			if (!StringUtils.hasText(normalizedParagraph)) {
				continue;
			}

			final String lower = normalizedParagraph.toLowerCase(Locale.ROOT);
			if (startsWithTitle(normalizedParagraph, title)) {
				final String withoutTitle = stripLeadingTitle(normalizedParagraph, title);
				if (!StringUtils.hasText(withoutTitle)) {
					continue;
				}
				candidate = withoutTitle;
			} else {
				candidate = normalizedParagraph;
			}

			if (containsAny(lower, HARD_STOP_MARKERS) && !kept.isEmpty()) {
				break;
			}
			if (isTextMarker(candidate)) {
				if (textMarkerSeen && !kept.isEmpty()) {
					break;
				}
				textMarkerSeen = true;
				continue;
			}
			if (shouldDiscard(candidate, lower)) {
				continue;
			}

			final String canonical = candidate.toLowerCase(Locale.ROOT);
			if (!seen.add(canonical)) {
				continue;
			}
			kept.add(candidate);
		}

		final String cleaned = trimHardStopTail(normalize(String.join("\n\n", kept)), media);
		if (cleaned.length() >= MIN_CLEAN_CONTENT_LENGTH) {
			return cleaned;
		}

		final String fallback = fallback(title, description);
		if (StringUtils.hasText(fallback)) {
			return fallback;
		}
		return cleaned;
	}

	private String trimMetadataPrefix(final String rawContent, final String title) {
		if (!StringUtils.hasText(title)) {
			return rawContent;
		}

		final List<Integer> occurrences = new ArrayList<>();
		int from = 0;
		while (from >= 0 && from < Math.min(rawContent.length(), PREFIX_SCAN_LIMIT)) {
			final int index = rawContent.indexOf(title, from);
			if (index < 0 || index >= PREFIX_SCAN_LIMIT) {
				break;
			}
			occurrences.add(index);
			from = index + title.length();
		}

		if (occurrences.isEmpty()) {
			return rawContent;
		}
		if (occurrences.size() > 1) {
			return rawContent.substring(occurrences.get(occurrences.size() - 1));
		}
		if (occurrences.getFirst() > 160) {
			return rawContent.substring(occurrences.getFirst());
		}
		return rawContent;
	}

	private List<String> splitParagraphs(final String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}

		final String[] split = MULTI_BREAK.split(text);
		if (split.length > 1) {
			return java.util.Arrays.stream(split)
				.map(NewsContentCleaner::normalize)
				.filter(StringUtils::hasText)
				.toList();
		}
		return List.of(normalize(text));
	}

	private boolean shouldDiscard(final String paragraph, final String lower) {
		if (containsAny(lower, DISCARD_MARKERS)) {
			return true;
		}
		if (COMMENT_TIMESTAMP_PATTERN.matcher(paragraph).find()) {
			return true;
		}
		if (URL_PATTERN.matcher(paragraph).results().count() >= 2) {
			return true;
		}
		if (TIME_PATTERN.matcher(paragraph).results().count() >= 2) {
			return true;
		}
		return paragraph.length() < 40 && !paragraph.endsWith(".") && !paragraph.endsWith("!") && !paragraph.endsWith("?");
	}

	private boolean startsWithTitle(final String paragraph, final String title) {
		final String normalizedTitle = normalize(title);
		return StringUtils.hasText(normalizedTitle) && paragraph.startsWith(normalizedTitle);
	}

	private String stripLeadingTitle(final String paragraph, final String title) {
		final String normalizedTitle = normalize(title);
		if (!StringUtils.hasText(normalizedTitle)) {
			return paragraph;
		}
		String candidate = paragraph;
		while (candidate.startsWith(normalizedTitle)) {
			candidate = normalize(candidate.substring(normalizedTitle.length()));
		}
		return normalize(candidate.replaceFirst("^[\\p{Punct}\\s]+", ""));
	}

	private boolean isTextMarker(final String paragraph) {
		final String lower = paragraph.toLowerCase(Locale.ROOT);
		return lower.startsWith("текст:") || lower.startsWith("text:");
	}

	private String fallback(final String title, final String description) {
		final String normalizedTitle = normalize(stripHtml(title));
		final String normalizedDescription = normalize(stripHtml(description));
		if (StringUtils.hasText(normalizedTitle) && StringUtils.hasText(normalizedDescription)) {
			return normalizedTitle + "\n\n" + normalizedDescription;
		}
		if (StringUtils.hasText(normalizedDescription)) {
			return normalizedDescription;
		}
		return normalizedTitle;
	}

	private static String stripHtml(final String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return Jsoup.parse(value).text();
	}

	private static boolean containsAny(final String value, final List<String> markers) {
		for (final String marker : markers) {
			if (value.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	private static String normalize(final String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return MULTI_SPACE.matcher(value.replace('\u00A0', ' ')).replaceAll(" ")
			.replaceAll("\\n{3,}", "\n\n")
			.strip();
	}

	private String stripInlineFragments(final String value) {
		String candidate = normalize(value);
		for (final String fragment : DISCARD_MARKERS) {
			candidate = candidate.replace(fragment, " ");
		}
		return normalize(candidate);
	}

	private String trimHardStopTail(final String text, final Media media) {
		if (!StringUtils.hasText(text)) {
			return "";
		}

		final int scanFrom = 0;
		int stop = text.length();
		for (final String marker : INLINE_HARD_STOP_MARKERS) {
			final int index = indexOfIgnoreCase(text, marker, scanFrom);
			if (index >= 0 && index < stop) {
				stop = index;
			}
		}

		final var commentMatcher = COMMENT_TIMESTAMP_PATTERN.matcher(text);
		if (commentMatcher.find(scanFrom) && commentMatcher.start() < stop) {
			stop = commentMatcher.start();
		}

		if (media == Media.TUVAONLINE) {
			final var tuvaDatesMatcher = TUVA_DATES_PATTERN.matcher(text);
			if (tuvaDatesMatcher.find(scanFrom) && tuvaDatesMatcher.start() < stop) {
				stop = tuvaDatesMatcher.start();
			}
		}

		return normalize(text.substring(0, stop));
	}

	private int indexOfIgnoreCase(final String text, final String marker, final int fromIndex) {
		if (!StringUtils.hasText(text) || !StringUtils.hasText(marker)) {
			return -1;
		}
		return text.toLowerCase(Locale.ROOT)
			.indexOf(marker.toLowerCase(Locale.ROOT), Math.max(0, fromIndex));
	}
}
