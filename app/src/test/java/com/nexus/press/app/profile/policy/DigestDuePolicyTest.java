package com.nexus.press.app.profile.policy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestDuePolicyTest {

	private DigestDuePolicy policy;
	private OffsetDateTime now;

	@BeforeEach
	void setUp() {
		policy = new DigestDuePolicy();
		now = OffsetDateTime.parse("2026-03-08T12:00:00Z");
	}

	@Test
	void dueImmediatelyAfterOnboardingWhenNoDeliveriesYet() {
		assertTrue(policy.isDue(profile(DigestFrequency.DAILY, true, OffsetDateTime.parse("2026-03-08T11:55:00Z"), null), now));
	}

	@Test
	void notDueWithoutOnboardingWhenNoDeliveriesYet() {
		assertFalse(policy.isDue(profile(DigestFrequency.DAILY, true, null, null), now));
	}

	@Test
	void dueWhenFrequencyIntervalPassed() {
		assertTrue(policy.isDue(
			profile(
				DigestFrequency.EVERY_2_DAYS,
				true,
				OffsetDateTime.parse("2026-03-01T10:00:00Z"),
				OffsetDateTime.parse("2026-03-06T10:00:00Z")
			),
			now
		));
	}

	@Test
	void notDueWhenFrequencyIntervalNotPassed() {
		assertFalse(policy.isDue(
			profile(
				DigestFrequency.EVERY_3_DAYS,
				true,
				OffsetDateTime.parse("2026-03-01T10:00:00Z"),
				OffsetDateTime.parse("2026-03-07T10:30:00Z")
			),
			now
		));
	}

	@Test
	void notDueWhenDigestDisabled() {
		assertFalse(policy.isDue(
			profile(
				DigestFrequency.DAILY,
				false,
				OffsetDateTime.parse("2026-03-01T10:00:00Z"),
				OffsetDateTime.parse("2026-03-07T10:30:00Z")
			),
			now
		));
	}

	private UserProfile profile(
		final DigestFrequency frequency,
		final boolean digestEnabled,
		final OffsetDateTime onboardedAt,
		final OffsetDateTime lastDeliveryAt
	) {
		return new UserProfile(
			UUID.randomUUID(),
			"12345",
			777L,
			"tester",
			"Test",
			"ru",
			"UTC",
			frequency,
			digestEnabled,
			onboardedAt,
			lastDeliveryAt,
			OffsetDateTime.parse("2026-03-01T10:00:00Z"),
			OffsetDateTime.parse("2026-03-08T10:00:00Z"),
			List.of("world")
		);
	}
}
