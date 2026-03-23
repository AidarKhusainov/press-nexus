package com.nexus.press.app.profile.policy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TopicSelectionPolicy {

	private static final Pattern TOPIC_SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
	private static final int MAX_TOPICS = 8;
	private static final Set<String> ALLOWED_TOPICS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
		"world",
		"russia",
		"economy",
		"business",
		"technology",
		"science",
		"politics",
		"society",
		"sports",
		"culture"
	)));

	public Set<String> supportedTopics() {
		return ALLOWED_TOPICS;
	}

	public List<String> normalize(final Collection<String> topics) {
		if (topics == null || topics.isEmpty()) {
			throw new IllegalArgumentException("Нужно выбрать хотя бы одну тему");
		}

		final var normalized = topics.stream()
			.filter(Objects::nonNull)
			.map(String::strip)
			.filter(StringUtils::hasText)
			.map(topic -> topic.toLowerCase(Locale.ROOT))
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Нужно выбрать хотя бы одну тему");
		}
		if (normalized.size() > MAX_TOPICS) {
			throw new IllegalArgumentException("Можно выбрать не более " + MAX_TOPICS + " тем");
		}
		if (!normalized.stream().allMatch(topic -> TOPIC_SLUG_PATTERN.matcher(topic).matches())) {
			throw new IllegalArgumentException("Темы должны быть в формате slug, например: world,economy,technology");
		}
		if (!ALLOWED_TOPICS.containsAll(normalized)) {
			throw new IllegalArgumentException("Есть неподдерживаемые темы. Допустимые темы: " + String.join(", ", ALLOWED_TOPICS));
		}
		return List.copyOf(normalized);
	}
}
