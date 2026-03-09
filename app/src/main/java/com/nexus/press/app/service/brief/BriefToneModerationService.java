package com.nexus.press.app.service.brief;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.nexus.press.app.service.brief.model.BriefImportance;
import org.springframework.stereotype.Service;

@Service
public class BriefToneModerationService {

	private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
	private static final Pattern EXCESSIVE_PUNCTUATION = Pattern.compile("[!?]{2,}");
	private static final Pattern UPPERCASE_WORD = Pattern.compile("\\b[\\p{Lu}]{4,}\\b");

	private static final List<String> HYPE_STEMS_RU = List.of(
		"шок",
		"сенсац",
		"скандал",
		"паник",
		"истерик",
		"ужас",
		"кошмар",
		"эксклюзив",
		"срочн",
		"взорвал"
	);
	private static final List<String> HYPE_STEMS_EN = List.of(
		"shocking",
		"sensational",
		"scandal",
		"panic",
		"hyster",
		"horror",
		"exclusive",
		"urgent",
		"you won't believe",
		"mind-blowing"
	);

	private static final List<String> CLICKBAIT_PHRASES_RU = List.of(
		"вы не поверите",
		"это взорвало интернет",
		"никто не ожидал"
	);
	private static final List<String> CLICKBAIT_PHRASES_EN = List.of(
		"you won't believe",
		"broke the internet",
		"nobody expected"
	);

	public ModerationDecision moderate(
		final String title,
		final String whatHappened,
		final String whyImportant,
		final String whatNext,
		final String language,
		final BriefImportance importance
	) {
		final String safeTitle = normalizeForDelivery(title);
		final String safeWhat = normalizeForDelivery(whatHappened);
		final String safeWhy = normalizeForDelivery(whyImportant);
		final String safeNext = normalizeForDelivery(whatNext);

		final String safeLanguage = normalizeLanguage(language);
		final int score = toneScore(safeTitle + " " + safeWhat + " " + safeWhy + " " + safeNext, safeLanguage);
		final int threshold = importance == BriefImportance.MUST_KNOW ? 7 : 5;

		return new ModerationDecision(
			score >= threshold,
			safeTitle,
			safeWhat,
			safeWhy,
			safeNext,
			score
		);
	}

	private int toneScore(final String text, final String language) {
		final String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
		final List<String> stems = "ru".equals(language) ? HYPE_STEMS_RU : HYPE_STEMS_EN;
		final List<String> phrases = "ru".equals(language) ? CLICKBAIT_PHRASES_RU : CLICKBAIT_PHRASES_EN;

		int score = 0;
		int matchedStems = 0;
		for (final String stem : stems) {
			if (lower.contains(stem)) {
				matchedStems++;
			}
		}
		score += Math.min(3, matchedStems);

		int matchedPhrases = 0;
		for (final String phrase : phrases) {
			if (lower.contains(phrase)) {
				matchedPhrases++;
			}
		}
		score += Math.min(4, matchedPhrases * 2);

		if (EXCESSIVE_PUNCTUATION.matcher(text).find()) {
			score += 1;
		}
		if (UPPERCASE_WORD.matcher(text).find()) {
			score += 1;
		}
		return score;
	}

	private String normalizeForDelivery(final String text) {
		if (text == null) {
			return "";
		}
		String normalized = MULTI_SPACE.matcher(text.replace('\n', ' ').replace('\r', ' ')).replaceAll(" ").strip();
		normalized = normalized.replaceAll("!{2,}", "!");
		normalized = normalized.replaceAll("\\?{2,}", "?");
		return normalized;
	}

	private String normalizeLanguage(final String language) {
		if (language == null || language.isBlank()) {
			return "ru";
		}
		final String normalized = language.strip().toLowerCase(Locale.ROOT);
		return normalized.startsWith("en") ? "en" : "ru";
	}

	public record ModerationDecision(
		boolean rejected,
		String moderatedTitle,
		String moderatedWhatHappened,
		String moderatedWhyImportant,
		String moderatedWhatNext,
		int toneScore
	) {
	}
}
