package com.nexus.press.app.service.profile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserProfile(
	UUID id,
	String telegramChatId,
	Long telegramUserId,
	String username,
	String firstName,
	String language,
	String timezone,
	DigestFrequency digestFrequency,
	boolean digestEnabled,
	OffsetDateTime onboardedAt,
	OffsetDateTime lastDeliveryAt,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt,
	List<String> topics
) {
}
