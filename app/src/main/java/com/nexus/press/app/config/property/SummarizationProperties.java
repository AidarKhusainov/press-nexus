package com.nexus.press.app.config.property;

import com.nexus.press.app.service.ai.summ.SummarizationProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "press.ai.summarization")
public record SummarizationProperties(
	SummarizationProvider provider
) {

	public SummarizationProperties {
		if (provider == null) {
			provider = SummarizationProvider.GEMINI;
		}
	}
}
