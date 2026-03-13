package com.nexus.press.app.service.ai.embed;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.List;

public interface EmbeddingService {

	Mono<float[]> embed(final String text);

	default Mono<List<float[]>> embedBatch(final List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return Mono.just(List.of());
		}
		return Flux.fromIterable(texts)
			.concatMap(this::embed)
			.collectList();
	}
}
