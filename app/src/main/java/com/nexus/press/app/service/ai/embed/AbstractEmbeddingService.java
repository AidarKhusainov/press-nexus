package com.nexus.press.app.service.ai.embed;

import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
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

	private static final int MAX_CHUNK_CHARS = 1500;

	private final WebClient webClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

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
		return embedBatch(List.of(text))
			.map(result -> result.isEmpty() ? new float[0] : result.getFirst());
	}

	@Override
	public Mono<List<float[]>> embedBatch(final List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return Mono.just(List.of());
		}

		final List<ChunkSlice> chunkSlices = new ArrayList<>(texts.size());
		final List<String> flattenedChunks = new ArrayList<>();
		for (final String text : texts) {
			final var chunks = chunk(text);
			chunkSlices.add(new ChunkSlice(flattenedChunks.size(), chunks.size()));
			flattenedChunks.addAll(chunks);
		}
		if (flattenedChunks.isEmpty()) {
			return Mono.just(emptyEmbeddings(texts.size()));
		}

		final Map<String, Object> payload = Map.of(
			"model", getModel(),
			"input", flattenedChunks
		);

		final long start = System.nanoTime();

		return webClient.post()
			.uri("/api/embed")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(payload)
			.retrieve()
			.bodyToMono(String.class)
			.map(this::parseEmbeddings)
			.map(embeddings -> aggregateEmbeddings(texts, chunkSlices, flattenedChunks.size(), embeddings))
			.doOnSuccess(vectors -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.info("Batch-эмбеддинг выполнен: texts={} chunks={} за {} мс",
					texts.size(), flattenedChunks.size(), elapsedMs);
			})
			.doOnError(e -> {
				final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
				log.warn("Ошибка batch-эмбединга после {} мс: texts={} chunks={}",
					elapsedMs, texts.size(), flattenedChunks.size(), e);
			});
	}

	public abstract String getModel();

	private List<String> chunk(final String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		return TextChunker.bySentences(text, MAX_CHUNK_CHARS);
	}

	private List<float[]> aggregateEmbeddings(
		final List<String> texts,
		final List<ChunkSlice> chunkSlices,
		final int expectedChunks,
		final List<float[]> embeddings
	) {
		if (embeddings.size() != expectedChunks) {
			throw new IllegalStateException("Expected " + expectedChunks + " embedding chunks but got " + embeddings.size());
		}

		final List<float[]> result = new ArrayList<>(texts.size());
		for (final ChunkSlice slice : chunkSlices) {
			if (slice.size() == 0) {
				result.add(new float[0]);
				continue;
			}
			final List<float[]> normalizedChunks = new ArrayList<>(slice.size());
			for (int i = slice.offset(); i < slice.offset() + slice.size(); i++) {
				normalizedChunks.add(VectorUtils.l2Normalize(embeddings.get(i)));
			}
			result.add(VectorUtils.l2Normalize(VectorUtils.meanPool(normalizedChunks)));
		}
		return result;
	}

	private List<float[]> parseEmbeddings(final String json) {
		try {
			final var root = objectMapper.readTree(json);
			final var arr = root.path("embeddings");
			if (!arr.isArray()) {
				throw new IllegalStateException("No 'embeddings' array in response: " + json);
			}
			final List<float[]> embeddings = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++) {
				final var embedding = arr.get(i);
				if (!embedding.isArray()) {
					throw new IllegalStateException("No embedding array at index " + i + " in response: " + json);
				}
				final float[] vec = new float[embedding.size()];
				for (int j = 0; j < embedding.size(); j++) {
					vec[j] = (float) embedding.get(j).asDouble();
				}
				embeddings.add(vec);
			}
			return embeddings;
		} catch (final Exception e) {
			throw new RuntimeException("Failed to parse embeddings JSON", e);
		}
	}

	private List<float[]> emptyEmbeddings(final int size) {
		final List<float[]> result = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			result.add(new float[0]);
		}
		return result;
	}

	private record ChunkSlice(int offset, int size) {
	}
}
