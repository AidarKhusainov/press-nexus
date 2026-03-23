package com.nexus.press.app.profile.persistence.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.List;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.TelegramUserContext;
import com.nexus.press.app.profile.model.UserProfile;

public interface UserProfileRepository {

	Mono<UserProfile> registerTelegramUser(TelegramUserContext context);

	Mono<UserProfile> updateTopics(String chatId, List<String> topics);

	Mono<UserProfile> updateFrequency(String chatId, DigestFrequency frequency);

	Mono<UserProfile> updateDigestEnabled(String chatId, boolean digestEnabled);

	Mono<UserProfile> findByChatId(String chatId);

	Flux<UserProfile> findDigestEnabledUsers();

	Mono<Void> markDigestDelivered(String chatId, OffsetDateTime deliveredAt);
}
