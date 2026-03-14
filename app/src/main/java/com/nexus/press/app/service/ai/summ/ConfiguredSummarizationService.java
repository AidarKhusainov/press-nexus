package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.property.SummarizationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class ConfiguredSummarizationService implements SummarizationService {

	private final Map<SummarizationProvider, ProviderSummarizationService> providers;
	private final SummarizationProperties properties;

	public ConfiguredSummarizationService(
		final List<ProviderSummarizationService> providerServices,
		final SummarizationProperties properties
	) {
		this.providers = new EnumMap<>(SummarizationProvider.class);
		providerServices.forEach(service -> this.providers.put(service.provider(), service));
		this.properties = properties;
	}

	@Override
	public Mono<String> summarize(final String text, final String lang) {
		return summarize(text, lang, SummarizationUseCase.AUTO_CLUSTER);
	}

	@Override
	public Mono<String> summarize(final String text, final String lang, final SummarizationUseCase useCase) {
		return summarizeDetailed(text, lang, useCase)
			.map(SummarizationResult::summary);
	}

	@Override
	public Mono<SummarizationResult> summarizeDetailed(
		final String text,
		final String lang,
		final SummarizationUseCase useCase
	) {
		final List<ProviderSummarizationService> orderedProviders = orderedProviders();
		if (orderedProviders.isEmpty()) {
			return Mono.error(new IllegalStateException("No configured summarization providers available"));
		}
		return tryProvider(orderedProviders, 0, text, lang, useCase, null);
	}

	@Override
	public String modelName() {
		return selectedProvider().modelName();
	}

	private ProviderSummarizationService selectedProvider() {
		final ProviderSummarizationService provider = providers.get(properties.provider());
		if (provider == null || !provider.isConfigured()) {
			throw new IllegalStateException("Summarization provider not configured: " + properties.provider());
		}
		return provider;
	}

	private Mono<SummarizationResult> tryProvider(
		final List<ProviderSummarizationService> orderedProviders,
		final int index,
		final String text,
		final String lang,
		final SummarizationUseCase useCase,
		final Throwable previousFailure
	) {
		if (index >= orderedProviders.size()) {
			return previousFailure != null
				? Mono.error(previousFailure)
				: Mono.error(new IllegalStateException("No configured summarization providers available"));
		}

		final ProviderSummarizationService provider = orderedProviders.get(index);
		return provider.summarizeDetailed(text, lang, useCase)
			.onErrorResume(ex -> {
				if (!isFallbackEligible(ex)) {
					return Mono.error(ex);
				}
				if (index + 1 >= orderedProviders.size()) {
					return Mono.error(ex);
				}
				log.warn(
					"Summarization provider {} failed for useCase={}, falling back to {}",
					provider.provider(),
					useCase,
					orderedProviders.get(index + 1).provider(),
					ex
				);
				return tryProvider(orderedProviders, index + 1, text, lang, useCase, ex);
			});
	}

	private List<ProviderSummarizationService> orderedProviders() {
		final LinkedHashSet<SummarizationProvider> providerOrder = new LinkedHashSet<>();
		providerOrder.add(properties.provider());
		providerOrder.addAll(properties.fallbackProviders());
		return providerOrder.stream()
			.map(providers::get)
			.filter(provider -> provider != null && provider.isConfigured())
			.toList();
	}

	private boolean isFallbackEligible(final Throwable throwable) {
		if (throwable instanceof SummarizationThrottledException
			|| throwable instanceof GeminiTransientException
			|| throwable instanceof WebClientRequestException) {
			return true;
		}
		if (throwable instanceof final WebClientResponseException responseException) {
			return responseException.getStatusCode().value() == 429
				|| responseException.getStatusCode().is5xxServerError();
		}
		return false;
	}
}
