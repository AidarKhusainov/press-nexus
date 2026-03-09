package com.nexus.press.app.config;

import com.nexus.press.app.config.property.InternalApiSecurityProperties;
import com.nexus.press.app.config.property.TelegramWebhookSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	InternalApiSecurityProperties.class,
	TelegramWebhookSecurityProperties.class
})
public class RequestAuthenticationConfiguration {

	@Bean
	RequestAuthenticationFilter requestAuthenticationFilter(
		final InternalApiSecurityProperties internalApiSecurityProperties,
		final TelegramWebhookSecurityProperties telegramWebhookSecurityProperties
	) {
		return new RequestAuthenticationFilter(internalApiSecurityProperties, telegramWebhookSecurityProperties);
	}
}
