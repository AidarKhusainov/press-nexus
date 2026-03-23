package com.nexus.press.app.profile.policy;

import java.time.OffsetDateTime;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.UserProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DigestDuePolicy {

	public boolean isDue(final UserProfile profile, final OffsetDateTime now) {
		if (profile == null || !profile.digestEnabled()) {
			return false;
		}
		if (!StringUtils.hasText(profile.telegramChatId())) {
			return false;
		}
		if (profile.lastDeliveryAt() == null) {
			return profile.onboardedAt() != null;
		}
		final OffsetDateTime baseTime = profile.lastDeliveryAt();
		final DigestFrequency frequency = profile.digestFrequency() == null ? DigestFrequency.DAILY : profile.digestFrequency();
		return !baseTime.plus(frequency.interval()).isAfter(now);
	}
}
