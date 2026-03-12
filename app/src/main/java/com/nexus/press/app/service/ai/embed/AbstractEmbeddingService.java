package com.nexus.press.app.service.ai.embed;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.util.TextChunker;
import com.nexus.press.app.util.VectorUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@AllArgsConstructor
public abstract class AbstractEmbeddingService implements EmbeddingService {

	private final WebClient webClient;

	public AbstractEmbeddingService(final WebClientConfig webClientConfig) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.OLLAMA);
	}

	/**
	 * Чанкинг → L2-норм каждого чанка → mean-pool → финальная L2-нормализация.
	 */
	@Override
	public Mono<float[]> embed(final String text) {
		if (text == null || text.isBlank()) {
			return Mono.just(new float[0]);
		}

		final int maxChunkChars = 1500;
		final var chunks = TextChunker.bySentences(text, maxChunkChars);

		return Flux.fromIterable(chunks)
			.concatMap(chunk -> embedOnce(chunk).map(VectorUtils::l2Normalize))
			.collectList()
			.map(VectorUtils::meanPool)
			.map(VectorUtils::l2Normalize);
	}

	private Mono<float[]> embedOnce(final String text) {
		final Map<String, Object> payload = Map.of(
			"model", getModel(),
			"prompt", text
		);

		final long start = System.nanoTime();

		return webClient.post()
			.uri("/api/embeddings")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(payload)
			.retrieve()
			.bodyToMono(String.class)
			.map(this::parseEmbedding)
			.doOnSuccess(vec -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.info("Эмбеддинг ({} dims) для текста длиной {} символов выполнен за {} мс",
					vec.length, text.length(), elapsedMs);
			})
			.doOnError(e -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.warn("Ошибка эмбединга после {} мс", elapsedMs, e);
			});
	}

	public abstract String getModel();

	private float[] parseEmbedding(final String json) {
		try {
			final var mapper = new ObjectMapper();
			final var root = mapper.readTree(json);
			final var arr = root.path("embedding");
			if (!arr.isArray()) {
				throw new IllegalStateException("No 'embedding' array in response: " + json);
			}
			final float[] vec = new float[arr.size()];
			for (int i = 0; i < arr.size(); i++) {
				vec[i] = (float) arr.get(i).asDouble();
			}
			return vec;
		} catch (final Exception e) {
			throw new RuntimeException("Failed to parse embedding JSON", e);
		}
	}
}
