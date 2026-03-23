package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Flux;
import java.time.OffsetDateTime;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import com.nexus.press.app.profile.policy.DigestDuePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FindUsersDueForDigest {

	private final UserProfileRepository userProfileRepository;
	private final DigestDuePolicy digestDuePolicy;

	public Flux<UserProfile> execute(final OffsetDateTime now) {
		final OffsetDateTime effectiveNow = now == null ? OffsetDateTime.now() : now;
		return userProfileRepository.findDigestEnabledUsers()
			.filter(profile -> digestDuePolicy.isDue(profile, effectiveNow));
	}
}
