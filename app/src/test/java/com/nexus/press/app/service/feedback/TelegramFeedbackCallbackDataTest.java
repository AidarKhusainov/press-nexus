package com.nexus.press.app.service.feedback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramFeedbackCallbackDataTest {

	@Test
	void parseValidPayloadWithNewsId() {
		final var parsed = TelegramFeedbackCallbackData.parse("fb|useful|news-42");
		assertTrue(parsed.isPresent());
		assertEquals(FeedbackEventType.USEFUL, parsed.get().eventType());
		assertEquals("news-42", parsed.get().newsId());
	}

	@Test
	void parseInvalidPayloadReturnsEmpty() {
		assertTrue(TelegramFeedbackCallbackData.parse("unknown").isEmpty());
		assertTrue(TelegramFeedbackCallbackData.parse("fb|wrong-type|news-42").isEmpty());
	}

	@Test
	void buildFallsBackToEventWithoutNewsIdWhenTooLong() {
		final String longNewsId = "n".repeat(70);
		assertEquals("fb|noise", TelegramFeedbackCallbackData.build("noise", longNewsId));
	}
}
