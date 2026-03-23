package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Mono;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class UpdateDigestEnabled {

	private final UserProfileRepository userProfileRepository;

	public Mono<UserProfile> execute(final String chatId, final boolean digestEnabled) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для обновления подписки"));
		}
		return userProfileRepository.updateDigestEnabled(chatId, digestEnabled);
	}
}
