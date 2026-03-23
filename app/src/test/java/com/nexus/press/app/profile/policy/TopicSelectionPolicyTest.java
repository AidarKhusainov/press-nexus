package com.nexus.press.app.profile.policy;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicSelectionPolicyTest {

	private final TopicSelectionPolicy policy = new TopicSelectionPolicy();

	@Test
	void normalizeDeduplicatesAndLowercasesTopics() {
		assertEquals(List.of("world", "economy"), policy.normalize(List.of("World", "economy", "world")));
	}

	@Test
	void normalizeRejectsUnsupportedTopics() {
		final var error = assertThrows(IllegalArgumentException.class, () -> policy.normalize(List.of("world", "movies")));
		assertEquals("Есть неподдерживаемые темы. Допустимые темы: world, russia, economy, business, technology, science, politics, society, sports, culture", error.getMessage());
	}
}
