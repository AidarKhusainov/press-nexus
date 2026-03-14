package com.nexus.press.app.config.property;

import java.time.Duration;
import java.util.List;
import com.nexus.press.app.service.ai.summ.SummarizationProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "press.ai.summarization")
public record SummarizationProperties(
	SummarizationProvider provider,
	List<SummarizationProvider> fallbackProviders,
	int autoClusterDailyBudget,
	int userFacingDailyBudget,
	int reserveDailyBudget,
	int autoClusterTopNPerDay,
	Duration autoClusterMaturity
) {

	public SummarizationProperties {
		if (provider == null) {
			provider = SummarizationProvider.GEMINI;
		}
		if (fallbackProviders == null || fallbackProviders.isEmpty()) {
			fallbackProviders = List.of(
				SummarizationProvider.GROQ,
				SummarizationProvider.CLOUDFLARE_WORKERS_AI,
				SummarizationProvider.MISTRAL
			);
		}
		if (autoClusterDailyBudget <= 0) {
			autoClusterDailyBudget = 12;
		}
		if (userFacingDailyBudget <= 0) {
			userFacingDailyBudget = 5;
		}
		if (reserveDailyBudget <= 0) {
			reserveDailyBudget = 3;
		}
		if (autoClusterTopNPerDay <= 0) {
			autoClusterTopNPerDay = 12;
		}
		if (autoClusterMaturity == null || autoClusterMaturity.isNegative() || autoClusterMaturity.isZero()) {
			autoClusterMaturity = Duration.ofMinutes(15);
		}
	}
}
