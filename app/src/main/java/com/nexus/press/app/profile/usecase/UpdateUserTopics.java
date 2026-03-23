package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Mono;
import java.util.Collection;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import com.nexus.press.app.profile.policy.TopicSelectionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class UpdateUserTopics {

	private final UserProfileRepository userProfileRepository;
	private final TopicSelectionPolicy topicSelectionPolicy;

	public Mono<UserProfile> execute(final String chatId, final Collection<String> topics) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для обновления тем"));
		}
		return userProfileRepository.updateTopics(chatId, topicSelectionPolicy.normalize(topics));
	}
}
