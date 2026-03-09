package com.nexus.press.app.service.profile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceDigestDueTest {

	@Mock
	private DatabaseClient db;

	private UserProfileService service;
	private OffsetDateTime now;

	@BeforeEach
	void setUp() {
		service = new UserProfileService(db);
		now = OffsetDateTime.parse("2026-03-08T12:00:00Z");
	}

	@Test
	void dueImmediatelyAfterOnboardingWhenNoDeliveriesYet() {
		final UserProfile profile = profile(
			DigestFrequency.DAILY,
			true,
			OffsetDateTime.parse("2026-03-08T11:55:00Z"),
			null
		);
		assertTrue(service.isDigestDue(profile, now));
	}

	@Test
	void notDueWithoutOnboardingWhenNoDeliveriesYet() {
		final UserProfile profile = profile(
			DigestFrequency.DAILY,
			true,
			null,
			null
		);
		assertFalse(service.isDigestDue(profile, now));
	}

	@Test
	void dueWhenFrequencyIntervalPassed() {
		final UserProfile profile = profile(
			DigestFrequency.EVERY_2_DAYS,
			true,
			OffsetDateTime.parse("2026-03-01T10:00:00Z"),
			OffsetDateTime.parse("2026-03-06T10:00:00Z")
		);
		assertTrue(service.isDigestDue(profile, now));
	}

	@Test
	void notDueWhenFrequencyIntervalNotPassed() {
		final UserProfile profile = profile(
			DigestFrequency.EVERY_3_DAYS,
			true,
			OffsetDateTime.parse("2026-03-01T10:00:00Z"),
			OffsetDateTime.parse("2026-03-07T10:30:00Z")
		);
		assertFalse(service.isDigestDue(profile, now));
	}

	@Test
	void notDueWhenDigestDisabled() {
		final UserProfile profile = profile(
			DigestFrequency.DAILY,
			false,
			OffsetDateTime.parse("2026-03-01T10:00:00Z"),
			OffsetDateTime.parse("2026-03-07T10:30:00Z")
		);
		assertFalse(service.isDigestDue(profile, now));
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
