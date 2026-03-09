package com.nexus.press.app.service.premium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumIntentCallbackDataTest {

	@Test
	void parseValidPayloadWithSegment() {
		final var parsed = PremiumIntentCallbackData.parse("pi|299|economy");
		assertTrue(parsed.isPresent());
		assertEquals(299, parsed.get().priceRub());
		assertEquals("economy", parsed.get().segment());
	}

	@Test
	void parseInvalidPayloadReturnsEmpty() {
		assertTrue(PremiumIntentCallbackData.parse("unknown").isEmpty());
		assertTrue(PremiumIntentCallbackData.parse("pi|250|economy").isEmpty());
		assertTrue(PremiumIntentCallbackData.parse("pi|x99|economy").isEmpty());
	}

	@Test
	void buildSanitizesSegment() {
		assertEquals("pi|199|economy_focus", PremiumIntentCallbackData.build(199, "Economy Focus"));
	}
}
