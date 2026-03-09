package com.nexus.press.app.service.brief;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.news.ReactiveNewsSimilarityStore;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.r2dbc.core.DatabaseClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DailyBriefQualityFixtureTest {

	private static final String FIXTURES_PATH = "fixtures/brief/daily-brief-quality-fixtures.json";

	private final DailyBriefFormatter formatter = new DailyBriefFormatter();
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@TestFactory
	Stream<DynamicTest> digestQualityFixturesRemainStable() {
		return loadFixtures().stream()
			.map(fixture -> DynamicTest.dynamicTest(fixture.name(), () -> assertFixture(fixture)));
	}

	private void assertFixture(final BriefFixture fixture) {
		final DailyBriefService service = new DailyBriefService(
			mock(DatabaseClient.class),
			mock(ReactiveNewsSimilarityStore.class),
			new BriefToneModerationService(),
			mock(AppMetrics.class)
		);

		final var items = service.selectItems(
			fixture.candidates().stream()
				.map(candidate -> new DailyBriefService.Candidate(
					candidate.id(),
					candidate.title(),
					candidate.url(),
					candidate.media(),
					OffsetDateTime.parse(candidate.eventAt()),
					candidate.summary()
				))
				.toList(),
			fixture.maxItems(),
			fixture.language(),
			java.util.Set.of(),
			fixture.nearDuplicateIds().entrySet().stream()
				.collect(java.util.stream.Collectors.toMap(
					Map.Entry::getKey,
					entry -> new java.util.HashSet<>(entry.getValue())
				))
		);

		assertEquals(fixture.expectedSelectedIds(), items.stream().map(item -> item.newsId()).toList());
		assertEquals(fixture.expectedMustKnowCount(), items.stream().filter(item -> item.importance() == BriefImportance.MUST_KNOW).count());
		assertEquals(fixture.expectedGoodToKnowCount(), items.stream().filter(item -> item.importance() == BriefImportance.GOOD_TO_KNOW).count());
		assertEquals(fixture.expectedDistinctMediaCount(), items.stream().map(item -> item.media()).distinct().count());
		for (final String excludedId : fixture.expectedExcludedIds()) {
			assertFalse(items.stream().map(item -> item.newsId()).toList().contains(excludedId));
		}

		for (final var item : items) {
			assertFalse(item.whatHappened().isBlank());
			assertFalse(item.whyImportant().isBlank());
			assertFalse(item.whatNext().isBlank());
		}

		final OffsetDateTime generatedAt = OffsetDateTime.parse(fixture.generatedAt());
		final var brief = new DailyBrief(
			generatedAt,
			generatedAt.minusHours(24),
			generatedAt,
			fixture.language(),
			items
		);
		final String message = formatter.toTelegramMessage(brief);

		assertEquals(items.size(), countOccurrences(message, "Что случилось:"));
		assertEquals(items.size(), countOccurrences(message, "Почему важно:"));
		assertEquals(items.size(), countOccurrences(message, "Что дальше:"));
		for (final String token : fixture.expectedMessageContains()) {
			assertTrue(message.contains(token), () -> "Message must contain: " + token);
		}
		for (final String token : fixture.expectedMessageNotContains()) {
			assertFalse(message.contains(token), () -> "Message must not contain: " + token);
		}
	}

	private List<BriefFixture> loadFixtures() {
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(FIXTURES_PATH)) {
			assertTrue(stream != null, "Fixture file must exist: " + FIXTURES_PATH);
			return objectMapper.readValue(stream, new TypeReference<>() {});
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to load brief quality fixtures", exception);
		}
	}

	private int countOccurrences(final String text, final String token) {
		int count = 0;
		int from = 0;
		while (from >= 0) {
			final int index = text.indexOf(token, from);
			if (index < 0) {
				break;
			}
			count++;
			from = index + token.length();
		}
		return count;
	}

	private record BriefFixture(
		String name,
		String language,
		int maxItems,
		String generatedAt,
		List<FixtureCandidate> candidates,
		Map<String, List<String>> nearDuplicateIds,
		List<String> expectedSelectedIds,
		List<String> expectedExcludedIds,
		int expectedMustKnowCount,
		int expectedGoodToKnowCount,
		int expectedDistinctMediaCount,
		List<String> expectedMessageContains,
		List<String> expectedMessageNotContains
	) {}

	private record FixtureCandidate(
		String id,
		String title,
		String url,
		String media,
		String eventAt,
		String summary
	) {}
}
