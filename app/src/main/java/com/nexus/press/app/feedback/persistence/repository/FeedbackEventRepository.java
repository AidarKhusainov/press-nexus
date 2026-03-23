package com.nexus.press.app.feedback.persistence.repository;

import com.nexus.press.app.feedback.model.FeedbackEventType;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface FeedbackEventRepository {

	Mono<Void> recordTelegramFeedback(
		String chatId,
		FeedbackEventType eventType,
		String newsId,
		String eventValue,
		Map<String, Object> payload
	);
}
