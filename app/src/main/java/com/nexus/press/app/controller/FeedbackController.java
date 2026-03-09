package com.nexus.press.app.controller;

import reactor.core.publisher.Mono;
import java.util.Map;
import com.nexus.press.app.service.feedback.FeedbackEventService;
import com.nexus.press.app.service.feedback.FeedbackEventType;
import com.nexus.press.app.web.generated.api.FeedbackApiDelegate;
import com.nexus.press.app.web.generated.model.ApiStatusResponse;
import com.nexus.press.app.web.generated.model.FeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackController implements FeedbackApiDelegate {

	private final FeedbackEventService feedbackEventService;

	@Override
	public Mono<ApiStatusResponse> postFeedbackEvent(
		final Mono<FeedbackRequest> feedbackRequest,
		final ServerWebExchange exchange
	) {
		final Mono<FeedbackRequest> safeBody = feedbackRequest == null ? Mono.empty() : feedbackRequest;
		return safeBody.defaultIfEmpty(new FeedbackRequest())
			.flatMap(request -> {
				if (request.getEventType() == null || request.getChatId() == null || request.getChatId().isBlank()) {
					return Mono.just(status(false, "invalid_request"));
				}

				final FeedbackEventType eventType;
				try {
					eventType = FeedbackEventType.fromToken(request.getEventType());
				} catch (final IllegalArgumentException ex) {
					return Mono.just(status(false, "invalid_event_type"));
				}

				return feedbackEventService.recordTelegramFeedback(
						request.getChatId(),
						eventType,
						request.getNewsId(),
						request.getEventValue(),
						request.getPayload() == null ? Map.of() : request.getPayload()
					)
					.thenReturn(status(true, null))
					.onErrorResume(ex -> {
						log.error("Failed to process feedback event", ex);
						return Mono.just(status(false, "processing_failed"));
					});
			});
	}

	private ApiStatusResponse status(final boolean ok, final String error) {
		final var response = new ApiStatusResponse(ok);
		response.setError(error);
		return response;
	}
}
