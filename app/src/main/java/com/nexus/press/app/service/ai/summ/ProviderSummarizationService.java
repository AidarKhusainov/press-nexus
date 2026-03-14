package com.nexus.press.app.service.ai.summ;

public interface ProviderSummarizationService extends SummarizationService {

	SummarizationProvider provider();

	default boolean isConfigured() {
		return true;
	}
}
