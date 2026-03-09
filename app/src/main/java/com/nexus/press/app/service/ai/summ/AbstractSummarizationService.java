package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@AllArgsConstructor
public abstract class AbstractSummarizationService implements SummarizationService {

	private final WebClient webClient;

	public AbstractSummarizationService(final WebClientConfig webClientConfig) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.OLLAMA);
	}

	public Mono<String> summarize(final String text, final String lang) {
		final long start = System.nanoTime();

		final var systemInstr = switch (lang) {
			case "ru" -> "Ты опытный журналист. Отвечай ТОЛЬКО на русском языке.";
			case "en" -> "You are an experienced journalist. Respond ONLY in English.";
			case "es" -> "Eres un periodista experimentado. Responde SOLO en español.";
			default   -> "You are an experienced journalist. Respond ONLY in English.";
		};

		final var userTask = switch (lang) {
			case "ru" -> "Summarize the news in 3–5 neutral sentences.";
			case "en" -> "Summarize the news in 3–5 neutral sentences, preserving facts and dates.";
			case "es" -> "Resume la noticia en 3–5 oraciones neutrales, preservando hechos y fechas.";
			default   -> "Summarize the news in 3–5 neutral sentences, preserving facts and dates.";
		};

		final var payload = Map.of(
			"model", getModel(),
			"messages", List.of(
				Map.of("role", "system", "content", systemInstr),
				Map.of("role", "user",   "content", userTask),
				Map.of("role", "user",   "content", text),
				Map.of("role", "user",   "content",
					switch (lang) {
						case "ru" -> "Отвечай только на русском.";
						case "en" -> "Answer only in English.";
						case "es" -> "Responde solo en español.";
						default   -> "Answer only in English.";
					})
			),
			"options", Map.of("think", false, "temperature", 0),
			"stream", false,
			"format", "json"
		);

		return webClient.post()
			.uri("/api/chat")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(payload)
			.retrieve()
			.bodyToMono(String.class)
			.map(this::parseSummary)
			.doOnSuccess(summary -> {
				final var elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.info("Суммаризация завершена: длина входа={} символов, длина выжимки={} символов, время={} мс",
					text.length(), summary.length(), elapsedMs);
			})
			.doOnError(e -> {
				final var elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.error("Ошибка суммаризации после {} мс", elapsedMs, e);
			});
	}

	public abstract String getModel();

	protected String parseSummary(final String responseJson) {
		try {
			final var node = new ObjectMapper().readTree(responseJson);
			return node.path("message").path("content").asText("");
		} catch (final Exception e) {
			log.error("Ошибка парсинга ответа от модели: {}", responseJson, e);
			return "";
		}
	}
}
