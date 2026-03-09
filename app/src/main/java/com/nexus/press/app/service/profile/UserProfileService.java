package com.nexus.press.app.service.profile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserProfileService {

	private static final Pattern TOPIC_SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
	private static final int MAX_TOPICS = 8;
	private static final Set<String> ALLOWED_TOPICS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
		"world",
		"russia",
		"economy",
		"business",
		"technology",
		"science",
		"politics",
		"society",
		"sports",
		"culture"
	)));

	private final DatabaseClient db;

	public Set<String> supportedTopics() {
		return ALLOWED_TOPICS;
	}

	public Mono<UserProfile> registerTelegramUser(final TelegramUserContext context) {
		if (context == null || !StringUtils.hasText(context.chatId())) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для регистрации пользователя"));
		}
		final String sql = """
			INSERT INTO users(telegram_chat_id, telegram_user_id, username, first_name, language)
			VALUES (:chatId, :telegramUserId, :username, :firstName, :language)
			ON CONFLICT (telegram_chat_id) DO UPDATE
			SET telegram_user_id = COALESCE(EXCLUDED.telegram_user_id, users.telegram_user_id),
			    username = COALESCE(EXCLUDED.username, users.username),
			    first_name = COALESCE(EXCLUDED.first_name, users.first_name),
			    language = COALESCE(EXCLUDED.language, users.language),
			    updated_at = now()
			RETURNING *
			""";

		var spec = db.sql(sql)
			.bind("chatId", context.chatId());
		spec = bindOrNull(spec, "telegramUserId", context.telegramUserId(), Long.class);
		spec = bindOrNull(spec, "username", normalizeNullable(context.username()), String.class);
		spec = bindOrNull(spec, "firstName", normalizeNullable(context.firstName()), String.class);
		spec = bindOrNull(spec, "language", normalizeNullable(context.language()), String.class);

		return spec.map((row, metadata) -> mapUserRow(row))
			.one()
			.flatMap(this::toUserProfile);
	}

	public Mono<UserProfile> updateTopics(final String chatId, final Collection<String> topics) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для обновления тем"));
		}
		final List<String> normalizedTopics = normalizeTopics(topics);

		return findUserRowByChatId(chatId)
			.flatMap(user -> db.sql("DELETE FROM user_topics WHERE user_id = :userId")
				.bind("userId", user.id())
				.fetch()
				.rowsUpdated()
				.thenMany(Flux.fromIterable(normalizedTopics)
					.concatMap(topic -> db.sql("""
						INSERT INTO user_topics(user_id, topic_slug)
						VALUES (:userId, :topic)
						ON CONFLICT (user_id, topic_slug) DO NOTHING
						""")
						.bind("userId", user.id())
						.bind("topic", topic)
						.fetch()
						.rowsUpdated()
						.then()))
				.then(findByChatId(chatId)));
	}

	public Mono<UserProfile> updateFrequency(final String chatId, final DigestFrequency frequency) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.error(new IllegalArgumentException("chatId обязателен для обновления частоты"));
		}
		if (frequency == null) {
			return Mono.error(new IllegalArgumentException("frequency обязателен для обновления частоты"));
		}
		return db.sql("""
			UPDATE users
			SET digest_frequency = :frequency,
			    onboarded_at = COALESCE(onboarded_at, now()),
			    updated_at = now()
			WHERE telegram_chat_id = :chatId
			""")
			.bind("frequency", frequency.getDbValue())
			.bind("chatId", chatId)
			.fetch()
			.rowsUpdated()
			.flatMap(rows -> rows > 0
				? findByChatId(chatId)
				: Mono.error(new IllegalArgumentException("Пользователь с таким chatId не найден")));
	}

	public Mono<UserProfile> findByChatId(final String chatId) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.empty();
		}
		return findUserRowByChatId(chatId)
			.flatMap(this::toUserProfile);
	}

	public Flux<UserProfile> findUsersDueForDigest(final OffsetDateTime now) {
		final OffsetDateTime effectiveNow = now == null ? OffsetDateTime.now() : now;
		return db.sql("""
			SELECT *
			FROM users
			WHERE digest_enabled = true
			""")
			.map((row, metadata) -> mapUserRow(row))
			.all()
			.flatMap(this::toUserProfile)
			.filter(profile -> isDigestDue(profile, effectiveNow));
	}

	public Mono<Void> markDigestDelivered(final String chatId, final OffsetDateTime deliveredAt) {
		if (!StringUtils.hasText(chatId)) {
			return Mono.empty();
		}
		return db.sql("""
			UPDATE users
			SET last_delivery_at = :deliveredAt,
			    updated_at = now()
			WHERE telegram_chat_id = :chatId
			""")
			.bind("deliveredAt", deliveredAt == null ? OffsetDateTime.now() : deliveredAt)
			.bind("chatId", chatId.strip())
			.fetch()
			.rowsUpdated()
			.then();
	}

	private Mono<UserRow> findUserRowByChatId(final String chatId) {
		return db.sql("SELECT * FROM users WHERE telegram_chat_id = :chatId")
			.bind("chatId", chatId)
			.map((row, metadata) -> mapUserRow(row))
			.one()
			.switchIfEmpty(Mono.error(new IllegalArgumentException("Пользователь с таким chatId не найден")));
	}

	boolean isDigestDue(final UserProfile profile, final OffsetDateTime now) {
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

	private Mono<UserProfile> toUserProfile(final UserRow userRow) {
		return db.sql("""
			SELECT topic_slug
			FROM user_topics
			WHERE user_id = :userId
			ORDER BY topic_slug
			""")
			.bind("userId", userRow.id())
			.map((row, metadata) -> row.get("topic_slug", String.class))
			.all()
			.filter(Objects::nonNull)
			.collectList()
			.map(topics -> new UserProfile(
				userRow.id(),
				userRow.telegramChatId(),
				userRow.telegramUserId(),
				userRow.username(),
				userRow.firstName(),
				userRow.language(),
				userRow.timezone(),
				DigestFrequency.fromDbValue(userRow.digestFrequency()).orElse(DigestFrequency.DAILY),
				userRow.digestEnabled(),
				userRow.onboardedAt(),
				userRow.lastDeliveryAt(),
				userRow.createdAt(),
				userRow.updatedAt(),
				topics
			));
	}

	private UserRow mapUserRow(final io.r2dbc.spi.Row row) {
		return new UserRow(
			row.get("id", UUID.class),
			row.get("telegram_chat_id", String.class),
			row.get("telegram_user_id", Long.class),
			row.get("username", String.class),
			row.get("first_name", String.class),
			row.get("language", String.class),
			row.get("timezone", String.class),
			row.get("digest_frequency", String.class),
			Boolean.TRUE.equals(row.get("digest_enabled", Boolean.class)),
			row.get("onboarded_at", OffsetDateTime.class),
			row.get("last_delivery_at", OffsetDateTime.class),
			row.get("created_at", OffsetDateTime.class),
			row.get("updated_at", OffsetDateTime.class)
		);
	}

	private List<String> normalizeTopics(final Collection<String> topics) {
		if (topics == null || topics.isEmpty()) {
			throw new IllegalArgumentException("Нужно выбрать хотя бы одну тему");
		}

		final var normalized = topics.stream()
			.filter(Objects::nonNull)
			.map(String::strip)
			.filter(StringUtils::hasText)
			.map(topic -> topic.toLowerCase(Locale.ROOT))
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Нужно выбрать хотя бы одну тему");
		}
		if (normalized.size() > MAX_TOPICS) {
			throw new IllegalArgumentException("Можно выбрать не более " + MAX_TOPICS + " тем");
		}
		if (!normalized.stream().allMatch(topic -> TOPIC_SLUG_PATTERN.matcher(topic).matches())) {
			throw new IllegalArgumentException("Темы должны быть в формате slug, например: world,economy,technology");
		}
		if (!ALLOWED_TOPICS.containsAll(normalized)) {
			throw new IllegalArgumentException("Есть неподдерживаемые темы. Допустимые темы: " + String.join(", ", ALLOWED_TOPICS));
		}
		return List.copyOf(normalized);
	}

	private String normalizeNullable(final String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}

	private <T> DatabaseClient.GenericExecuteSpec bindOrNull(
		final DatabaseClient.GenericExecuteSpec spec,
		final String name,
		final T value,
		final Class<T> type
	) {
		return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
	}

	private record UserRow(
		UUID id,
		String telegramChatId,
		Long telegramUserId,
		String username,
		String firstName,
		String language,
		String timezone,
		String digestFrequency,
		boolean digestEnabled,
		OffsetDateTime onboardedAt,
		OffsetDateTime lastDeliveryAt,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt
	) {
	}
}
