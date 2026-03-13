package com.nexus.press.app.service.ai.summ;

import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.MistralProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class MistralSummarizationService extends AbstractOpenAiCompatibleSummarizationService {

	private final MistralProperties properties;

	public MistralSummarizationService(
		final WebClientConfig webClientConfig,
		final MistralProperties properties
	) {
		super(webClientConfig, HttpClientName.MISTRAL);
		this.properties = properties;
	}

	@Override
	public SummarizationProvider provider() {
		return SummarizationProvider.MISTRAL;
	}

	@Override
	protected String model() {
		return properties.model();
	}

	@Override
	protected String path() {
		return "/v1/chat/completions";
	}

	@Override
	protected void configureHeaders(final HttpHeaders headers) {
		headers.setBearerAuth(properties.apiKey());
	}
}
