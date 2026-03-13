package com.nexus.press.app.service.ai.summ;

import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.CloudflareWorkersAiProperties;
import com.nexus.press.app.config.property.HttpClientName;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class CloudflareWorkersAiSummarizationService extends AbstractOpenAiCompatibleSummarizationService {

	private final CloudflareWorkersAiProperties properties;

	public CloudflareWorkersAiSummarizationService(
		final WebClientConfig webClientConfig,
		final CloudflareWorkersAiProperties properties
	) {
		super(webClientConfig, HttpClientName.CLOUDFLARE_WORKERS_AI);
		this.properties = properties;
	}

	@Override
	public SummarizationProvider provider() {
		return SummarizationProvider.CLOUDFLARE_WORKERS_AI;
	}

	@Override
	protected String model() {
		return properties.model();
	}

	@Override
	protected String path() {
		return "/client/v4/accounts/" + properties.accountId() + "/ai/v1/chat/completions";
	}

	@Override
	protected void configureHeaders(final HttpHeaders headers) {
		headers.setBearerAuth(properties.apiToken());
	}
}
