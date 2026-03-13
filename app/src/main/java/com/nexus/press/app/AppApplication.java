package com.nexus.press.app;

import com.nexus.press.app.config.property.CloudflareWorkersAiProperties;
import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.GroqProperties;
import com.nexus.press.app.config.property.MistralProperties;
import com.nexus.press.app.config.property.NewsPlatformProperties;
import com.nexus.press.app.config.property.OllamaProperties;
import com.nexus.press.app.config.property.ProductReportProperties;
import com.nexus.press.app.config.property.SummarizationProperties;
import com.nexus.press.app.config.property.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({
	CloudflareWorkersAiProperties.class,
	GeminiProperties.class,
	GroqProperties.class,
	MistralProperties.class,
	NewsPlatformProperties.class,
	OllamaProperties.class,
	TelegramProperties.class,
	ProductReportProperties.class,
	SummarizationProperties.class
})
public class AppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppApplication.class, args);
	}

}
