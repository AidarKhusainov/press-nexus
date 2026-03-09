package com.nexus.press.app.service.delivery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramDeliveryService {

	private static final int MAX_TELEGRAM_MESSAGE_LENGTH = 3900;

	private final WebClientConfig webClientConfig;

	public Mono<Void> sendMessage(final String botToken, final String chatId, final String text) {
		return sendMessage(botToken, chatId, text, null);
	}

	public Mono<Void> sendMessage(
		final String botToken,
		final String chatId,
		final String text,
		final Map<String, Object> replyMarkup
	) {
		if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank() || text == null || text.isBlank()) {
			return Mono.empty();
		}

		final WebClient client = webClientConfig.getWebClient(HttpClientName.TELEGRAM);
		final List<String> chunks = (replyMarkup == null || replyMarkup.isEmpty())
			? splitBySize(text, MAX_TELEGRAM_MESSAGE_LENGTH)
			: List.of(limitLength(text, MAX_TELEGRAM_MESSAGE_LENGTH));

		return Flux.fromIterable(chunks)
			.concatMap(chunk -> client.post()
				.uri("/bot{token}/sendMessage", botToken)
				.bodyValue(buildSendMessageBody(chatId, chunk, replyMarkup))
				.retrieve()
				.bodyToMono(String.class)
				.doOnNext(body -> log.debug("Telegram response chatId={}: {}", chatId, body))
				.then())
			.then();
	}

	public Mono<Void> answerCallbackQuery(final String botToken, final String callbackQueryId, final String text) {
		if (botToken == null || botToken.isBlank() || callbackQueryId == null || callbackQueryId.isBlank()) {
			return Mono.empty();
		}
		final WebClient client = webClientConfig.getWebClient(HttpClientName.TELEGRAM);
		final var body = new HashMap<String, Object>();
		body.put("callback_query_id", callbackQueryId);
		if (text != null && !text.isBlank()) {
			body.put("text", limitLength(text, 180));
		}
		return client.post()
			.uri("/bot{token}/answerCallbackQuery", botToken)
			.bodyValue(body)
			.retrieve()
			.bodyToMono(String.class)
			.doOnNext(response -> log.debug("Telegram callback answer response callbackId={}: {}", callbackQueryId, response))
			.then();
	}

	private Map<String, Object> buildSendMessageBody(
		final String chatId,
		final String text,
		final Map<String, Object> replyMarkup
	) {
		final var body = new HashMap<String, Object>();
		body.put("chat_id", chatId);
		body.put("text", text);
		body.put("disable_web_page_preview", true);
		if (replyMarkup != null && !replyMarkup.isEmpty()) {
			body.put("reply_markup", replyMarkup);
		}
		return body;
	}

	private List<String> splitBySize(final String text, final int maxLength) {
		if (text.length() <= maxLength) return List.of(text);
		final var chunks = new ArrayList<String>();
		int from = 0;
		while (from < text.length()) {
			int to = Math.min(text.length(), from + maxLength);
			if (to < text.length()) {
				final int splitAt = text.lastIndexOf("\n\n", to);
				if (splitAt > from + 200) {
					to = splitAt;
				}
			}
			chunks.add(text.substring(from, to).strip());
			from = to;
		}
		return chunks;
	}

	private String limitLength(final String text, final int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, Math.max(0, maxLength - 1)).strip() + "…";
	}
}
