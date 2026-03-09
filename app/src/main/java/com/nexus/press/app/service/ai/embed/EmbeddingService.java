package com.nexus.press.app.service.ai.embed;

import reactor.core.publisher.Mono;

public interface EmbeddingService {

	Mono<float[]> embed(final String text);
}
