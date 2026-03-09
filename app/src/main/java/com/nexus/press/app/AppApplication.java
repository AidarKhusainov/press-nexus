package com.nexus.press.app;

import com.nexus.press.app.config.property.GeminiProperties;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.ProductReportProperties;
import com.nexus.press.app.config.property.TelegramDeliveryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({
	HttpClientProperties.class,
	GeminiProperties.class,
	TelegramDeliveryProperties.class,
	ProductReportProperties.class
})
public class AppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppApplication.class, args);
	}

}
