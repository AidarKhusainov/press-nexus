package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Mono;
import com.nexus.press.app.profile.model.TelegramUserContext;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RegisterTelegramUser {

	private final UserProfileRepository userProfileRepository;

	public Mono<UserProfile> execute(final TelegramUserContext context) {
		if (context == null || !StringUtils.hasText(context.chatId())) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для регистрации пользователя"));
		}
		return userProfileRepository.registerTelegramUser(context);
	}
}
