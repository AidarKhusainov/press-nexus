package com.nexus.press.app.service.profile;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigestFrequencyTest {

	@Test
	void intervalMatchesExpectedDuration() {
		assertEquals(Duration.ofDays(1), DigestFrequency.DAILY.interval());
		assertEquals(Duration.ofDays(2), DigestFrequency.EVERY_2_DAYS.interval());
		assertEquals(Duration.ofDays(3), DigestFrequency.EVERY_3_DAYS.interval());
	}
}
