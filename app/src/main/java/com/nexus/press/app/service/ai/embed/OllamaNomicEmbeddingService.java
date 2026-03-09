package com.nexus.press.app.service.ai.embed;

import com.nexus.press.app.config.WebClientConfig;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.stereotype.Service;

@Service
public final class OllamaNomicEmbeddingService extends AbstractEmbeddingService implements EmbeddingService {

	public OllamaNomicEmbeddingService(final WebClientConfig webClientConfig) {
		super(webClientConfig);
	}

	@Override
	public String getModel() {
		return OllamaModel.NOMIC_EMBED_TEXT.getName();
	}
}
