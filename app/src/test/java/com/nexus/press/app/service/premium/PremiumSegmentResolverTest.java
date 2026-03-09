package com.nexus.press.app.service.premium;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PremiumSegmentResolverTest {

	private final PremiumSegmentResolver resolver = new PremiumSegmentResolver();

	@Test
	void resolvesEconomySegment() {
		assertEquals("economy", resolver.resolve(List.of("economy", "business")));
	}

	@Test
	void resolvesTechSegment() {
		assertEquals("tech", resolver.resolve(List.of("technology", "science")));
	}

	@Test
	void resolvesMixedSegmentWhenTopicsSpanMultipleBuckets() {
		assertEquals("mixed", resolver.resolve(List.of("world", "technology")));
	}

	@Test
	void resolvesGeneralSegmentWhenTopicsAreEmpty() {
		assertEquals("general", resolver.resolve(List.of()));
	}
}
