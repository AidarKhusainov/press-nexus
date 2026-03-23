package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Mono;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class FindUserProfileByChatId {

	private final UserProfileRepository userProfileRepository;

	public Mono<UserProfile> execute(final String chatId) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.empty();
		}
		return userProfileRepository.findByChatId(chatId);
	}
}
