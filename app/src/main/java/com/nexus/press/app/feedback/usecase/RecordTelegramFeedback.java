package com.nexus.press.app.feedback.usecase;

import com.nexus.press.app.feedback.model.FeedbackEventType;
import com.nexus.press.app.feedback.persistence.repository.FeedbackEventRepository;
import reactor.core.publisher.Mono;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RecordTelegramFeedback {

	private final FeedbackEventRepository feedbackEventRepository;

	public Mono<Void> execute(
		final String chatId,
		final FeedbackEventType eventType,
		final String newsId,
		final String eventValue,
		final Map<String, Object> payload
	) {
		if (!StringUtils.hasText(chatId) || eventType == null) {
			return Mono.empty();
		}

		return feedbackEventRepository.recordTelegramFeedback(
			chatId.strip(),
			eventType,
			normalizeNullable(newsId),
			normalizeNullable(eventValue),
			payload
		);
	}

	private String normalizeNullable(final String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}
}
