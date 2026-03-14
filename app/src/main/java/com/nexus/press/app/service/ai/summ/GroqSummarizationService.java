package com.nexus.press.app.service.ai.summ;

import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.GroqProperties;
import com.nexus.press.app.config.property.HttpClientName;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GroqSummarizationService extends AbstractOpenAiCompatibleSummarizationService {

	private final GroqProperties properties;

	public GroqSummarizationService(
		final WebClientConfig webClientConfig,
		final GroqProperties properties
	) {
		super(webClientConfig, HttpClientName.GROQ);
		this.properties = properties;
	}

	@Override
	public SummarizationProvider provider() {
		return SummarizationProvider.GROQ;
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(properties.apiKey()) && StringUtils.hasText(properties.model());
	}

	@Override
	protected String model() {
		return properties.model();
	}

	@Override
	protected String path() {
		return "/chat/completions";
	}

	@Override
	protected void configureHeaders(final HttpHeaders headers) {
		headers.setBearerAuth(properties.apiKey());
	}
}
