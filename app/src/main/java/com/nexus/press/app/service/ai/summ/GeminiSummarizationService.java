package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.HttpClientName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component("geminiSummarizationService")
@RequiredArgsConstructor
public class GeminiSummarizationService implements ProviderSummarizationService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final WebClientConfig webClientConfig;
	private final GeminiProperties properties;

	@Override
	public SummarizationProvider provider() {
		return SummarizationProvider.GEMINI;
	}

	@Override
	public String modelName() {
		return provider().name() + ":" + properties.model();
	}

	@Override
	public Mono<String> summarize(final String text, final String lang) {
		if (text == null || text.isBlank()) {
			return Mono.just("");
		}

		final Map<String, Object> payload = Map.of(
			"system_instruction", Map.of(
				"parts", List.of(Map.of("text", SummarizationPromptSupport.systemInstruction(lang)))
			),
			"contents", List.of(Map.of(
				"role", "user",
				"parts", List.of(Map.of("text", SummarizationPromptSupport.userPrompt(text, lang)))
			)),
			"generationConfig", Map.of(
				"temperature", 0.2,
				"topP", 0.95,
				"thinkingConfig", Map.of("thinkingBudget", 0)
			)
		);

		final long start = System.nanoTime();

		return webClientConfig.getWebClient(HttpClientName.GEMINI)
			.post()
			.uri("/models/" + properties.model() + ":generateContent")
			.headers(headers -> {
				headers.set("x-goog-api-key", properties.apiKey());
				headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
				headers.set("x-goog-api-client", "press-nexus/0.1.0");
			})
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(payload)
			.retrieve()
			.bodyToMono(String.class)
			.map(GeminiSummarizationService::parseSummary)
			.doOnSuccess(summary -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.info("Gemini суммаризация: вход={} симв, выжимка={} симв, {} мс", text.length(), summary.length(), elapsedMs);
			})
			.doOnError(ex -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.warn("Gemini суммаризация завершилась ошибкой после {} мс", elapsedMs, ex);
			});
	}

	static String parseSummary(final String json) {
		try {
			final JsonNode parts = OBJECT_MAPPER.readTree(json)
				.path("candidates")
				.path(0)
				.path("content")
				.path("parts");
			if (!parts.isArray()) {
				return "";
			}

			final StringBuilder summary = new StringBuilder();
			for (final JsonNode part : parts) {
				final String text = part.path("text").asText("");
				if (!text.isBlank()) {
					if (!summary.isEmpty()) {
						summary.append(' ');
					}
					summary.append(text.trim());
				}
			}
			return summary.toString();
		} catch (final Exception ex) {
			log.warn("Gemini ответ не удалось распарсить: {}", json, ex);
			return "";
		}
	}
}
