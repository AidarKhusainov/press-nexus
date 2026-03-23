package com.nexus.press.app.ai.integration.summ;

import com.nexus.press.app.ai.model.SummarizationProvider;

public interface ProviderSummarizationService extends SummarizationService {

	SummarizationProvider provider();

	default boolean isConfigured() {
		return true;
	}
}
