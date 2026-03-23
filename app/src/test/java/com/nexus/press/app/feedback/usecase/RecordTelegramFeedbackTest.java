package com.nexus.press.app.feedback.usecase;

import com.nexus.press.app.feedback.model.FeedbackEventType;
import com.nexus.press.app.feedback.persistence.repository.FeedbackEventRepository;
import reactor.core.publisher.Mono;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordTelegramFeedbackTest {

	@Mock
	private FeedbackEventRepository feedbackEventRepository;

	@Test
	void blankChatIdSkipsPersistence() {
		final var useCase = new RecordTelegramFeedback(feedbackEventRepository);

		useCase.execute("  ", FeedbackEventType.USEFUL, "news-1", "inline_button", Map.of()).block();

		verifyNoInteractions(feedbackEventRepository);
	}

	@Test
	void nullEventTypeSkipsPersistence() {
		final var useCase = new RecordTelegramFeedback(feedbackEventRepository);

		useCase.execute("12345", null, "news-1", "inline_button", Map.of()).block();

		verifyNoInteractions(feedbackEventRepository);
	}

	@Test
	void validRequestNormalizesOptionalValuesBeforePersisting() {
		final var useCase = new RecordTelegramFeedback(feedbackEventRepository);
		final Map<String, Object> payload = Map.of("source", "telegram");
		when(feedbackEventRepository.recordTelegramFeedback("12345", FeedbackEventType.CLICK, "news-1", "inline_button", payload))
			.thenReturn(Mono.empty());

		useCase.execute(" 12345 ", FeedbackEventType.CLICK, " news-1 ", " inline_button ", payload).block();

		verify(feedbackEventRepository).recordTelegramFeedback("12345", FeedbackEventType.CLICK, "news-1", "inline_button", payload);
	}
}
