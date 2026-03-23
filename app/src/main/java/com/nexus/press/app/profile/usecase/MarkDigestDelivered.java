package com.nexus.press.app.profile.usecase;

import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import com.nexus.press.app.profile.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class MarkDigestDelivered {

	private final UserProfileRepository userProfileRepository;

	public Mono<Void> execute(final String chatId, final OffsetDateTime deliveredAt) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.empty();
		}
		return userProfileRepository.markDigestDelivered(chatId, deliveredAt == null ? OffsetDateTime.now() : deliveredAt);
	}
}
