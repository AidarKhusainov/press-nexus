package com.nexus.press.app.config.property;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPropertiesBindingTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void bindsGroupedPlatformProperties() {
		contextRunner
			.withPropertyValues(
				"platform.ollama.http.base-url=http://localhost:11434/",
				"platform.ollama.http.timeout.connection=5m",
				"platform.ollama.http.timeout.read=5m",
				"platform.ollama.http.retry.max-attempts=10",
				"platform.ollama.http.retry.backoff=5s",
				"platform.ollama.http.retry.jitter=0.5",
				"platform.news.http.base-url=https://example.com",
				"platform.news.http.timeout.connection=1m",
				"platform.news.http.timeout.read=1m",
				"platform.news.http.retry.max-attempts=3",
				"platform.news.http.retry.backoff=1s",
				"platform.news.http.retry.jitter=0.5",
				"platform.gemini.http.base-url=https://generativelanguage.googleapis.com/v1beta",
				"platform.gemini.http.timeout.connection=60s",
				"platform.gemini.http.timeout.read=5m",
				"platform.gemini.http.retry.max-attempts=5",
				"platform.gemini.http.retry.backoff=1s",
				"platform.gemini.http.retry.jitter=0.5",
				"platform.gemini.api-key=gemini-key",
				"platform.gemini.model=gemini-2.5-flash",
				"platform.groq.http.base-url=https://api.groq.com/openai/v1",
				"platform.groq.http.timeout.connection=60s",
				"platform.groq.http.timeout.read=2m",
				"platform.groq.http.retry.max-attempts=5",
				"platform.groq.http.retry.backoff=1s",
				"platform.groq.http.retry.jitter=0.5",
				"platform.groq.api-key=groq-key",
				"platform.groq.model=llama-3.3-70b-versatile",
				"platform.cloudflare-workers-ai.http.base-url=https://api.cloudflare.com",
				"platform.cloudflare-workers-ai.http.timeout.connection=60s",
				"platform.cloudflare-workers-ai.http.timeout.read=2m",
				"platform.cloudflare-workers-ai.http.retry.max-attempts=5",
				"platform.cloudflare-workers-ai.http.retry.backoff=1s",
				"platform.cloudflare-workers-ai.http.retry.jitter=0.5",
				"platform.cloudflare-workers-ai.account-id=acc-123",
				"platform.cloudflare-workers-ai.api-token=cf-token",
				"platform.cloudflare-workers-ai.model=@cf/meta/llama-3.1-8b-instruct",
				"platform.mistral.http.base-url=https://api.mistral.ai",
				"platform.mistral.http.timeout.connection=60s",
				"platform.mistral.http.timeout.read=2m",
				"platform.mistral.http.retry.max-attempts=5",
				"platform.mistral.http.retry.backoff=1s",
				"platform.mistral.http.retry.jitter=0.5",
				"platform.mistral.api-key=mistral-key",
				"platform.mistral.model=mistral-small-latest",
				"platform.telegram.http.base-url=https://api.telegram.org",
				"platform.telegram.http.timeout.connection=30s",
				"platform.telegram.http.timeout.read=30s",
				"platform.telegram.http.retry.max-attempts=3",
				"platform.telegram.http.retry.backoff=1s",
				"platform.telegram.http.retry.jitter=0.3",
				"platform.telegram.bot.token=bot-token",
				"platform.telegram.webhook.secret-token=secret-token",
				"platform.telegram.delivery.enabled=true",
				"platform.telegram.delivery.chat-ids=chat-1,chat-2",
				"platform.telegram.delivery.interval=24h",
				"platform.telegram.delivery.lookback=24h",
				"platform.telegram.delivery.max-items=7",
				"platform.telegram.delivery.language=ru"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(OllamaProperties.class).http().baseUrl()).isEqualTo("http://localhost:11434/");
				assertThat(context.getBean(NewsPlatformProperties.class).http().baseUrl()).isEqualTo("https://example.com");
				assertThat(context.getBean(GeminiProperties.class).model()).isEqualTo("gemini-2.5-flash");
				assertThat(context.getBean(GroqProperties.class).apiKey()).isEqualTo("groq-key");
				assertThat(context.getBean(CloudflareWorkersAiProperties.class).accountId()).isEqualTo("acc-123");
				assertThat(context.getBean(MistralProperties.class).model()).isEqualTo("mistral-small-latest");
				assertThat(context.getBean(TelegramProperties.class).bot().token()).isEqualTo("bot-token");
				assertThat(context.getBean(TelegramProperties.class).delivery().chatIds()).containsExactly("chat-1", "chat-2");
				assertThat(context.getBean(TelegramProperties.class).webhook().secretToken()).isEqualTo("secret-token");
			});
	}

	@EnableConfigurationProperties({
		OllamaProperties.class,
		NewsPlatformProperties.class,
		GeminiProperties.class,
		GroqProperties.class,
		CloudflareWorkersAiProperties.class,
		MistralProperties.class,
		TelegramProperties.class
	})
	static class TestConfiguration {
	}
}
