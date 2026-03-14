package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;

public interface SummarizationService {

	Mono<String> summarize(final String text, final String lang);

	default Mono<String> summarize(final String text, final String lang, final SummarizationUseCase useCase) {
		return summarize(text, lang);
	}

	default Mono<SummarizationResult> summarizeDetailed(final String text, final String lang) {
		return summarizeDetailed(text, lang, SummarizationUseCase.AUTO_CLUSTER);
	}

	default Mono<SummarizationResult> summarizeDetailed(
		final String text,
		final String lang,
		final SummarizationUseCase useCase
	) {
		return summarize(text, lang, useCase)
			.map(summary -> new SummarizationResult(summary, modelName()));
	}

	default String modelName() {
		return getClass().getSimpleName();
	}
}
