package com.nexus.press.app.premium.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class PostgresPremiumIntentEventRepository implements PremiumIntentEventRepository {

	private static final String CHANNEL_TELEGRAM = "telegram";
	private static final String INSERT_PREMIUM_INTENT_SQL = """
		INSERT INTO premium_intent_events(
		    user_id,
		    price_rub,
		    segment,
		    channel,
		    source,
		    payload,
		    occurred_at
		)
		VALUES (
		    (SELECT id FROM users WHERE telegram_chat_id = :chatId),
		    :priceRub,
		    :segment,
		    :channel,
		    :source,
		    CAST(:payload AS jsonb),
		    :occurredAt
		)
		""";

	private final DatabaseClient db;
	private final ObjectMapper objectMapper;

	@Override
	public Mono<Void> recordTelegramIntent(
		final String chatId,
		final int priceRub,
		final String segment,
		final String source,
		final Map<String, Object> payload
	) {
		var spec = db.sql(INSERT_PREMIUM_INTENT_SQL)
			.bind("chatId", chatId.strip())
			.bind("priceRub", priceRub)
			.bind("channel", CHANNEL_TELEGRAM)
			.bind("occurredAt", OffsetDateTime.now());

		spec = bindOrNull(spec, "segment", normalizeNullable(segment), String.class);
		spec = bindOrNull(spec, "source", normalizeNullable(source), String.class);
		spec = bindOrNull(spec, "payload", toJsonOrNull(payload), String.class);

		return spec.fetch()
			.rowsUpdated()
			.then();
	}

	private String toJsonOrNull(final Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (final JsonProcessingException ex) {
			throw new IllegalArgumentException("Не удалось сериализовать payload premium intent события", ex);
		}
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
}
