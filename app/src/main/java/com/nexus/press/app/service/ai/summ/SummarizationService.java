package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;

public interface SummarizationService {

	Mono<String> summarize(final String text, final String lang);

	default String modelName() {
		return getClass().getSimpleName();
	}
}
