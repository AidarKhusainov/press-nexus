package com.nexus.press.app.service.feedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FeedbackEventService {

	private static final String CHANNEL_TELEGRAM = "telegram";
	private static final String INSERT_FEEDBACK_EVENT_SQL = """
		INSERT INTO feedback_events(
		    user_id,
		    news_id,
		    channel,
		    event_type,
		    event_value,
		    payload,
		    occurred_at
		)
		VALUES (
		    (SELECT id FROM users WHERE telegram_chat_id = :chatId),
		    :newsId,
		    :channel,
		    :eventType,
		    :eventValue,
		    CAST(:payload AS jsonb),
		    :occurredAt
		)
		""";

	private final DatabaseClient db;
	private final ObjectMapper objectMapper;

	public Mono<Void> recordTelegramFeedback(
		final String chatId,
		final FeedbackEventType eventType,
		final String newsId,
		final String eventValue,
		final Map<String, Object> payload
	) {
		if (!StringUtils.hasText(chatId) || eventType == null) {
			return Mono.empty();
		}

		var spec = db.sql(INSERT_FEEDBACK_EVENT_SQL)
			.bind("chatId", chatId.strip())
			.bind("channel", CHANNEL_TELEGRAM)
			.bind("eventType", eventType.dbValue())
			.bind("occurredAt", OffsetDateTime.now());

		spec = bindOrNull(spec, "newsId", normalizeNullable(newsId), String.class);
		spec = bindOrNull(spec, "eventValue", normalizeNullable(eventValue), String.class);
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
			throw new IllegalArgumentException("Не удалось сериализовать payload feedback-события", ex);
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
