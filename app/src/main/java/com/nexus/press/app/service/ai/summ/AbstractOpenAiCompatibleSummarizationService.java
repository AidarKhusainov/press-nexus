package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
public abstract class AbstractOpenAiCompatibleSummarizationService implements ProviderSummarizationService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final WebClient webClient;

	protected AbstractOpenAiCompatibleSummarizationService(
		final WebClientConfig webClientConfig,
		final HttpClientName clientName
	) {
		this.webClient = webClientConfig.getWebClient(clientName);
	}

	@Override
	public Mono<String> summarize(final String text, final String lang) {
		if (text == null || text.isBlank()) {
			return Mono.just("");
		}

		final long start = System.nanoTime();
		final Map<String, Object> payload = Map.of(
			"model", model(),
			"messages", List.of(
				Map.of("role", "system", "content", SummarizationPromptSupport.systemInstruction(lang)),
				Map.of("role", "user", "content", SummarizationPromptSupport.userPrompt(text, lang))
			),
			"temperature", 0.2,
			"stream", false
		);

		return webClient.post()
			.uri(path())
			.headers(headers -> configureHeaders(headers))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(payload)
			.retrieve()
			.bodyToMono(String.class)
			.map(this::parseSummary)
			.doOnSuccess(summary -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.info(
					"{} суммаризация: вход={} симв, выжимка={} симв, {} мс",
					SummarizationPromptSupport.providerLogLabel(provider()),
					text.length(),
					summary.length(),
					elapsedMs
				);
			})
			.doOnError(ex -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.warn(
					"{} суммаризация завершилась ошибкой после {} мс",
					SummarizationPromptSupport.providerLogLabel(provider()),
					elapsedMs,
					ex
				);
			});
	}

	@Override
	public String modelName() {
		return provider().name() + ":" + model();
	}

	protected abstract String model();

	protected abstract String path();

	protected abstract void configureHeaders(org.springframework.http.HttpHeaders headers);

	protected String parseSummary(final String responseJson) {
		try {
			final JsonNode contentNode = OBJECT_MAPPER.readTree(responseJson)
				.path("choices")
				.path(0)
				.path("message")
				.path("content");

			if (contentNode.isTextual()) {
				return contentNode.asText("");
			}

			if (!contentNode.isArray()) {
				return "";
			}

			final List<String> parts = new ArrayList<>();
			for (final JsonNode item : contentNode) {
				final String text = item.path("text").asText("");
				if (!text.isBlank()) {
					parts.add(text);
				}
			}
			return String.join(" ", parts).trim();
		} catch (final Exception ex) {
			log.warn("{} ответ не удалось распарсить: {}", provider(), responseJson, ex);
			return "";
		}
	}
}
