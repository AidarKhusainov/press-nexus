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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Primary
@Component("geminiSummarizationService")
@RequiredArgsConstructor
public class GeminiSummarizationService implements SummarizationService {

    private final WebClientConfig webClientConfig;
    private final GeminiProperties props;

    @Override
    public Mono<String> summarize(final String text, final String lang) {
        if (text == null || text.isBlank()) return Mono.just("");

        final var systemInstr = switch (lang) {
            case "ru" -> "Ты опытный журналист. Отвечай ТОЛЬКО на русском языке.";
            case "en" -> "You are an experienced journalist. Respond ONLY in English.";
            case "es" -> "Eres un periodista experimentado. Responde SOLO en español.";
            default   -> "You are an experienced journalist. Respond ONLY in English.";
        };

        final var userTask = switch (lang) {
            case "ru" -> "Кратко перескажи новость в 3–5 нейтральных предложениях, сохраняя факты и даты.";
            case "en" -> "Summarize the news in 3–5 neutral sentences, preserving facts and dates.";
            case "es" -> "Resume la noticia en 3–5 oraciones neutrales, preservando hechos y fechas.";
            default   -> "Summarize the news in 3–5 neutral sentences, preserving facts and dates.";
        };

        final var contentText = String.join("\n\n", List.of(systemInstr, userTask, text));

        final Map<String, Object> payload = Map.of(
            // systemInstruction поддерживается в v1beta
            "systemInstruction", Map.of(
                "role", "system",
                "parts", List.of(Map.of("text", systemInstr))
            ),
            "contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", contentText))
            )),
            "generationConfig", Map.of(
                "temperature", 0.2,
                "topK", 40,
                "topP", 0.95
            )
        );

        final var client = webClientConfig.getWebClient(HttpClientName.GEMINI);
        final var path = "/models/" + props.model() + ":generateContent";
        final var uri = UriComponentsBuilder.fromPath(path)
            .queryParam("key", props.apiKey())
            .build(true)
            .toUriString();

        final long start = System.nanoTime();

        return client.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(String.class)
            .map(GeminiSummarizationService::parseSummary)
            .doOnSuccess(summary -> {
                final var elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("Gemini суммаризация: вход={} симв, выжимка={} симв, {} мс", text.length(), summary.length(), elapsedMs);
            });
    }

    private static String parseSummary(final String json) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode root = mapper.readTree(json);
            final JsonNode cands = root.path("candidates");
            if (cands.isArray() && cands.size() > 0) {
                final JsonNode parts = cands.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    return parts.get(0).path("text").asText("");
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}

