package com.nexus.press.app.config.property;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.nexus.press.app.service.ai.summ.SummarizationProvider;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizationPropertiesBindingTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void bindsSummarizationRoutingBudgetsAndFallbacks() {
		contextRunner
			.withPropertyValues(
				"press.ai.summarization.provider=GEMINI",
				"press.ai.summarization.fallback-providers=GROQ,CLOUDFLARE_WORKERS_AI,MISTRAL",
				"press.ai.summarization.auto-cluster-daily-budget=12",
				"press.ai.summarization.user-facing-daily-budget=5",
				"press.ai.summarization.reserve-daily-budget=3",
				"press.ai.summarization.auto-cluster-top-n-per-day=12",
				"press.ai.summarization.auto-cluster-maturity=15m",
				"press.news.pipeline.summary-maturity=15m"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				final SummarizationProperties summarizationProperties = context.getBean(SummarizationProperties.class);
				assertThat(summarizationProperties.provider()).isEqualTo(SummarizationProvider.GEMINI);
				assertThat(summarizationProperties.fallbackProviders()).containsExactly(
					SummarizationProvider.GROQ,
					SummarizationProvider.CLOUDFLARE_WORKERS_AI,
					SummarizationProvider.MISTRAL
				);
				assertThat(summarizationProperties.autoClusterDailyBudget()).isEqualTo(12);
				assertThat(summarizationProperties.userFacingDailyBudget()).isEqualTo(5);
				assertThat(summarizationProperties.reserveDailyBudget()).isEqualTo(3);
				assertThat(summarizationProperties.autoClusterTopNPerDay()).isEqualTo(12);
				assertThat(summarizationProperties.autoClusterMaturity()).isEqualTo(Duration.ofMinutes(15));
				assertThat(context.getBean(NewsPipelineProperties.class).getSummaryMaturity()).isEqualTo(Duration.ofMinutes(15));
			});
	}

	@EnableConfigurationProperties({
		SummarizationProperties.class,
		NewsPipelineProperties.class
	})
	static class TestConfiguration {
	}
}
