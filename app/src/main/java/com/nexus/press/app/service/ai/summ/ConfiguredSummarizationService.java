package com.nexus.press.app.service.ai.summ;

import reactor.core.publisher.Mono;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.property.SummarizationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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
		return selectedProvider().summarize(text, lang);
	}

	@Override
	public String modelName() {
		return selectedProvider().modelName();
	}

	private ProviderSummarizationService selectedProvider() {
		final ProviderSummarizationService provider = providers.get(properties.provider());
		if (provider == null) {
			throw new IllegalStateException("Summarization provider not configured: " + properties.provider());
		}
		return provider;
	}
}
