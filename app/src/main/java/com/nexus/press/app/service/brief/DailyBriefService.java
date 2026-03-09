package com.nexus.press.app.service.brief;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.brief.model.DailyBriefItem;
import com.nexus.press.app.service.news.NewsSimilarityStore;
import com.nexus.press.app.service.news.ReactiveNewsSimilarityStore;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DailyBriefService {

	private static final String RECENT_SUMMARIZED_NEWS_SQL = """
		SELECT n.id,
		       n.title,
		       n.url,
		       n.media,
		       COALESCE(n.published_at, n.fetched_at) AS event_at,
		       ns.summary
		FROM news n
		JOIN LATERAL (
		    SELECT s.summary
		    FROM news_summary s
		    WHERE s.news_id = n.id
		      AND (s.lang = :lang OR s.lang = 'ru' OR s.lang = 'en')
		    ORDER BY (s.lang = :lang) DESC, s.created_at DESC
		    LIMIT 1
		) ns ON TRUE
		WHERE n.status_summary = 'DONE'
		  AND COALESCE(n.published_at, n.fetched_at) >= :fromTs
		ORDER BY COALESCE(n.published_at, n.fetched_at) DESC
		LIMIT :lim
		""";

	private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
	private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
	private static final double BRIEF_NEAR_DUPLICATE_MIN_SCORE = 0.82d;
	private static final int MIN_NEAR_DUPLICATE_TOKEN_OVERLAP = 5;
	private static final double MIN_NEAR_DUPLICATE_TOKEN_RATIO = 0.6d;
	private static final Set<String> MAJOR_MEDIA = Set.of(
		"BBC", "CNN", "REUTERS", "NYTIMES", "DW",
		"RIA", "TASS", "RBK", "KOMMERSANT", "VEDOMOSTI"
	);
	private static final Map<String, List<String>> TOPIC_KEYWORDS = Map.of(
		"world", List.of("world", "global", "международ", "миров"),
		"russia", List.of("росси", "russia", "рф", "москв", "кремл"),
		"economy", List.of("economy", "economic", "эконом", "инфляц", "ставк", "ввп", "рынок"),
		"business", List.of("business", "компан", "корпорац", "рынок", "инвест", "акци"),
		"technology", List.of("technology", "tech", "ai", "ии", "технолог", "цифров"),
		"science", List.of("science", "research", "наук", "исследован", "учен"),
		"politics", List.of("politic", "government", "policy", "полит", "правительств", "госдум", "парламент"),
		"society", List.of("society", "общест", "жител", "социальн", "медицина", "образован"),
		"sports", List.of("sport", "match", "football", "хокке", "спорт", "матч", "лига", "турнир"),
		"culture", List.of("culture", "movie", "music", "театр", "культур", "фильм", "книг", "музык")
	);

	private static final List<String> MUST_KEYWORDS_RU = List.of(
		"срочн", "удар", "войн", "санкц", "выбор", "землетряс", "пожар", "инфляц", "ставк", "курс", "атак", "погиб", "тариф"
	);
	private static final List<String> MUST_KEYWORDS_EN = List.of(
		"breaking", "war", "attack", "sanction", "election", "earthquake", "fire", "inflation", "interest rate", "killed", "tariff"
	);

	private final DatabaseClient db;
	private final ReactiveNewsSimilarityStore similarityStore;
	private final BriefToneModerationService briefToneModerationService;
	private final AppMetrics appMetrics;

	public Mono<DailyBrief> buildBrief(final Duration lookback, final int maxItems, final String language) {
		return buildBrief(lookback, maxItems, language, List.of());
	}

	public Mono<DailyBrief> buildBrief(
		final Duration lookback,
		final int maxItems,
		final String language,
		final Collection<String> topics
	) {
		final var safeLanguage = normalizeLanguage(language);
		final var safeLookback = (lookback == null || lookback.isNegative() || lookback.isZero())
			? Duration.ofHours(24)
			: lookback;
		final int safeLimit = Math.max(1, Math.min(maxItems, 20));
		final Set<String> normalizedTopics = normalizeTopics(topics);
		final int queryLimit = Math.max(safeLimit * 8, safeLimit);

		final var to = OffsetDateTime.now();
		final var from = to.minus(safeLookback);

		return fetchCandidates(from, queryLimit, safeLanguage)
			.collectList()
			.flatMap(candidates -> findNearDuplicateIds(candidates)
				.map(nearDuplicateIds -> new DailyBrief(
					OffsetDateTime.now(),
					from,
					to,
					safeLanguage,
					selectItems(candidates, safeLimit, safeLanguage, normalizedTopics, nearDuplicateIds)
				)));
	}

	private Flux<Candidate> fetchCandidates(final OffsetDateTime from, final int limit, final String language) {
		return db.sql(RECENT_SUMMARIZED_NEWS_SQL)
			.bind("lang", language)
			.bind("fromTs", from)
			.bind("lim", limit)
			.map((row, md) -> new Candidate(
				row.get("id", String.class),
				row.get("title", String.class),
				row.get("url", String.class),
				row.get("media", String.class),
				row.get("event_at", OffsetDateTime.class),
				row.get("summary", String.class)
			))
			.all();
	}

	List<DailyBriefItem> selectItems(
		final List<Candidate> candidates,
		final int maxItems,
		final String language,
		final Set<String> topics
	) {
		return selectItems(candidates, maxItems, language, topics, Map.of());
	}

	List<DailyBriefItem> selectItems(
		final List<Candidate> candidates,
		final int maxItems,
		final String language,
		final Set<String> topics,
		final Map<String, Set<String>> nearDuplicateIds
	) {
		final List<DailyBriefItem> selected = new ArrayList<>();
		final Set<String> seenTitles = new HashSet<>();
		final Set<String> seenUrls = new HashSet<>();
		final Set<String> selectedIds = new HashSet<>();
		final List<CandidateFingerprint> selectedFingerprints = new ArrayList<>();

		for (final var candidate : candidates) {
			if (selected.size() >= maxItems) break;
			final String title = clean(candidate.title());
			final String url = clean(candidate.url());
			if (title.isBlank() || url.isBlank()) continue;

			final String titleKey = dedupKey(title);
			if (!seenTitles.add(titleKey)) continue;
			if (!seenUrls.add(url)) continue;

			final var parts = splitSummary(title, candidate.summary(), language);
			if (!matchesAnyTopic(candidate, parts, topics)) continue;
			if (isNearDuplicate(candidate, title, parts, selectedIds, nearDuplicateIds, selectedFingerprints)) continue;
			final var importance = scoreImportance(candidate, parts, language);
			final var moderated = briefToneModerationService.moderate(
				title,
				parts.whatHappened(),
				parts.whyImportant(),
				parts.whatNext(),
				language,
				importance
			);
			final String importanceTag = importance == BriefImportance.MUST_KNOW ? "must_know" : "good_to_know";
			if (moderated.rejected()) {
				appMetrics.briefToneModerationEvent("rejected", importanceTag);
				continue;
			}
			appMetrics.briefToneModerationEvent("accepted", importanceTag);

			selected.add(new DailyBriefItem(
				candidate.id(),
				moderated.moderatedTitle(),
				url,
				clean(candidate.media()),
				candidate.eventAt(),
				importance,
				moderated.moderatedWhatHappened(),
				moderated.moderatedWhyImportant(),
				moderated.moderatedWhatNext()
			));
			if (candidate.id() != null && !candidate.id().isBlank()) {
				selectedIds.add(candidate.id());
			}
			selectedFingerprints.add(candidateFingerprint(title, parts));
		}
		return selected;
	}

	private Mono<Map<String, Set<String>>> findNearDuplicateIds(final List<Candidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return Mono.just(Map.of());
		}
		final Set<String> candidateIds = candidates.stream()
			.map(Candidate::id)
			.filter(id -> id != null && !id.isBlank())
			.collect(Collectors.toSet());
		if (candidateIds.isEmpty()) {
			return Mono.just(Map.of());
		}

		return Flux.fromIterable(candidateIds)
			.flatMap(id -> similarityStore.neighbors(id, BRIEF_NEAR_DUPLICATE_MIN_SCORE)
				.map(NewsSimilarityStore.SimilarItem::id)
				.filter(candidateIds::contains)
				.map(neighborId -> Map.entry(id, neighborId)))
			.collectList()
			.map(pairs -> {
				final Map<String, Set<String>> nearDuplicateIds = new HashMap<>();
				for (final var pair : pairs) {
					nearDuplicateIds.computeIfAbsent(pair.getKey(), ignored -> new HashSet<>()).add(pair.getValue());
					nearDuplicateIds.computeIfAbsent(pair.getValue(), ignored -> new HashSet<>()).add(pair.getKey());
				}
				return nearDuplicateIds;
			});
	}

	private boolean isNearDuplicate(
		final Candidate candidate,
		final String title,
		final SummaryParts parts,
		final Set<String> selectedIds,
		final Map<String, Set<String>> nearDuplicateIds,
		final List<CandidateFingerprint> selectedFingerprints
	) {
		final String candidateId = candidate.id();
		if (candidateId != null && !candidateId.isBlank()) {
			final Set<String> duplicates = nearDuplicateIds.getOrDefault(candidateId, Set.of());
			for (final String selectedId : selectedIds) {
				if (duplicates.contains(selectedId)) {
					return true;
				}
			}
		}

		final CandidateFingerprint candidateFingerprint = candidateFingerprint(title, parts);
		for (final CandidateFingerprint selectedFingerprint : selectedFingerprints) {
			if (candidateFingerprint.signature().equals(selectedFingerprint.signature())) {
				return true;
			}
			if (overlapRatio(candidateFingerprint.tokens(), selectedFingerprint.tokens()) >= MIN_NEAR_DUPLICATE_TOKEN_RATIO
				&& overlapCount(candidateFingerprint.tokens(), selectedFingerprint.tokens()) >= MIN_NEAR_DUPLICATE_TOKEN_OVERLAP) {
				return true;
			}
		}
		return false;
	}

	private boolean matchesAnyTopic(final Candidate candidate, final SummaryParts parts, final Set<String> topics) {
		if (topics == null || topics.isEmpty()) {
			return true;
		}
		final String haystack = (safeLower(candidate.title()) + " " +
			safeLower(candidate.summary()) + " " +
			safeLower(candidate.media()) + " " +
			safeLower(parts.whatHappened()) + " " +
			safeLower(parts.whyImportant()) + " " +
			safeLower(parts.whatNext())).strip();
		for (final String topic : topics) {
			final List<String> keywords = TOPIC_KEYWORDS.get(topic);
			if (keywords == null || keywords.isEmpty()) {
				continue;
			}
			for (final String keyword : keywords) {
				if (haystack.contains(keyword)) {
					return true;
				}
			}
		}
		return false;
	}

	private Set<String> normalizeTopics(final Collection<String> topics) {
		if (topics == null || topics.isEmpty()) {
			return Set.of();
		}
		final Set<String> normalized = new HashSet<>();
		for (final String topic : topics) {
			if (topic == null || topic.isBlank()) {
				continue;
			}
			final String key = topic.strip().toLowerCase(Locale.ROOT);
			if (TOPIC_KEYWORDS.containsKey(key)) {
				normalized.add(key);
			}
		}
		return normalized;
	}

	private BriefImportance scoreImportance(final Candidate candidate, final SummaryParts parts, final String language) {
		int score = 0;
		final String haystack = (candidate.title() + " " + candidate.summary()).toLowerCase(Locale.ROOT);
		final var keywords = "ru".equals(language) ? MUST_KEYWORDS_RU : MUST_KEYWORDS_EN;

		for (final var keyword : keywords) {
			if (haystack.contains(keyword)) {
				score += 2;
				break;
			}
		}
		if (candidate.media() != null && MAJOR_MEDIA.contains(candidate.media().toUpperCase(Locale.ROOT))) score += 1;
		if (candidate.eventAt() != null && candidate.eventAt().isAfter(OffsetDateTime.now().minusHours(6))) score += 1;
		if ((parts.whyImportant().length() + parts.whatNext().length()) > 180) score += 1;

		return score >= 3 ? BriefImportance.MUST_KNOW : BriefImportance.GOOD_TO_KNOW;
	}

	private SummaryParts splitSummary(final String title, final String summary, final String language) {
		final String cleanSummary = clean(summary);
		if (cleanSummary.isBlank()) {
			return fallbackFromTitle(title, language);
		}

		final String[] sentences = SENTENCE_SPLIT.split(cleanSummary);
		final String happened = sentenceOrFallback(sentences, 0, title);
		final String important = sentenceOrFallback(sentences, 1, defaultWhy(language));
		final String next = sentenceOrFallback(sentences, 2, defaultNext(language));

		return new SummaryParts(
			limitLength(happened, 280),
			limitLength(important, 280),
			limitLength(next, 280)
		);
	}

	private SummaryParts fallbackFromTitle(final String title, final String language) {
		return new SummaryParts(
			limitLength(clean(title), 280),
			defaultWhy(language),
			defaultNext(language)
		);
	}

	private String sentenceOrFallback(final String[] sentences, final int index, final String fallback) {
		if (index >= 0 && index < sentences.length) {
			final String value = clean(sentences[index]);
			if (!value.isBlank()) return value;
		}
		return clean(fallback);
	}

	private String defaultWhy(final String language) {
		return "ru".equals(language)
			? "Событие влияет на общую повестку и может затронуть повседневные решения."
			: "This event affects the broader agenda and can influence daily decisions.";
	}

	private String defaultNext(final String language) {
		return "ru".equals(language)
			? "Следим за подтверждениями, официальными заявлениями и дальнейшими последствиями."
			: "Watch for confirmations, official statements, and follow-up impacts.";
	}

	private String normalizeLanguage(final String language) {
		if (language == null || language.isBlank()) return "ru";
		final String normalized = language.strip().toLowerCase(Locale.ROOT);
		if (normalized.startsWith("en")) return "en";
		return "ru";
	}

	private String dedupKey(final String text) {
		return clean(text)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^\\p{L}\\p{Nd} ]", "")
			.replaceAll("\\s+", " ")
			.strip();
	}

	private String clean(final String value) {
		if (value == null) return "";
		return MULTI_SPACE.matcher(value.replace('\n', ' ').replace('\r', ' ')).replaceAll(" ").strip();
	}

	private String safeLower(final String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private String limitLength(final String value, final int maxLength) {
		if (value == null || value.length() <= maxLength) return value;
		return value.substring(0, Math.max(0, maxLength - 1)).strip() + "…";
	}

	private CandidateFingerprint candidateFingerprint(final String title, final SummaryParts parts) {
		final String combined = dedupKey(title + " " + parts.whatHappened() + " " + parts.whyImportant());
		return new CandidateFingerprint(combined, tokens(combined));
	}

	private Set<String> tokens(final String normalized) {
		if (normalized == null || normalized.isBlank()) {
			return Set.of();
		}
		final Set<String> tokens = new HashSet<>();
		for (final String token : normalized.split(" ")) {
			if (token.length() >= 4) {
				tokens.add(normalizeToken(token));
			}
		}
		return tokens;
	}

	private String normalizeToken(final String token) {
		if (token == null || token.isBlank()) {
			return "";
		}
		final String cleanToken = token.strip();
		return cleanToken.length() <= 6 ? cleanToken : cleanToken.substring(0, 6);
	}

	private int overlapCount(final Set<String> left, final Set<String> right) {
		if (left.isEmpty() || right.isEmpty()) {
			return 0;
		}
		int overlap = 0;
		for (final String token : left) {
			if (right.contains(token)) {
				overlap++;
			}
		}
		return overlap;
	}

	private double overlapRatio(final Set<String> left, final Set<String> right) {
		final int minSize = Math.min(left.size(), right.size());
		if (minSize == 0) {
			return 0d;
		}
		return (double) overlapCount(left, right) / minSize;
	}

	static record Candidate(
		String id,
		String title,
		String url,
		String media,
		OffsetDateTime eventAt,
		String summary
	) {}

	private record CandidateFingerprint(
		String signature,
		Set<String> tokens
	) {}

	private record SummaryParts(
		String whatHappened,
		String whyImportant,
		String whatNext
	) {}
}
