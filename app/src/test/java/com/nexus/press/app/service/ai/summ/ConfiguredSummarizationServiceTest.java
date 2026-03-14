package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfiguredSummarizationServiceTest {

	@Test
	void fallsBackToNextConfiguredProviderWhenPrimaryThrottles() {
		final var service = new ConfiguredSummarizationService(
			List.of(
				new StubProvider(
					SummarizationProvider.GEMINI,
					true,
					Mono.error(new SummarizationThrottledException("quota exhausted")),
					"GEMINI:gemini"
				),
				new StubProvider(
					SummarizationProvider.GROQ,
					true,
					Mono.just(new SummarizationResult("Fallback summary", "GROQ:llama")),
					"GROQ:llama"
				)
			),
			new com.nexus.press.app.config.property.SummarizationProperties(
				SummarizationProvider.GEMINI,
				List.of(SummarizationProvider.GROQ),
				12,
				5,
				3,
				12,
				Duration.ofMinutes(15)
			)
		);

		final var result = service.summarizeDetailed("text", "ru", SummarizationUseCase.AUTO_CLUSTER).block();

		assertEquals("Fallback summary", result.summary());
		assertEquals("GROQ:llama", result.modelName());
	}

	@Test
	void failsWhenNoProviderIsConfigured() {
		final var service = new ConfiguredSummarizationService(
			List.of(new StubProvider(
				SummarizationProvider.GEMINI,
				false,
				Mono.just(new SummarizationResult("ignored", "ignored")),
				"GEMINI:gemini"
			)),
			new com.nexus.press.app.config.property.SummarizationProperties(
				SummarizationProvider.GEMINI,
				List.of(SummarizationProvider.GROQ),
				12,
				5,
				3,
				12,
				Duration.ofMinutes(15)
			)
		);

		assertThrows(IllegalStateException.class, () -> service.summarizeDetailed("text", "ru").block());
	}

	private static final class StubProvider implements ProviderSummarizationService {

		private final SummarizationProvider provider;
		private final boolean configured;
		private final Mono<SummarizationResult> response;
		private final String modelName;

		private StubProvider(
			final SummarizationProvider provider,
			final boolean configured,
			final Mono<SummarizationResult> response,
			final String modelName
		) {
			this.provider = provider;
			this.configured = configured;
			this.response = response;
			this.modelName = modelName;
		}

		@Override
		public Mono<String> summarize(final String text, final String lang) {
			return response.map(SummarizationResult::summary);
		}

		@Override
		public Mono<SummarizationResult> summarizeDetailed(
			final String text,
			final String lang,
			final SummarizationUseCase useCase
		) {
			return response;
		}

		@Override
		public String modelName() {
			return modelName;
		}

		@Override
		public SummarizationProvider provider() {
			return provider;
		}

		@Override
		public boolean isConfigured() {
			return configured;
		}
	}
}
