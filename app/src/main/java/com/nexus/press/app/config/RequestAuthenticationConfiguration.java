package com.nexus.press.app.config;

import com.nexus.press.app.config.property.InternalApiSecurityProperties;
import com.nexus.press.app.config.property.TelegramProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	InternalApiSecurityProperties.class,
	TelegramProperties.class
})
public class RequestAuthenticationConfiguration {

	@Bean
	RequestAuthenticationFilter requestAuthenticationFilter(
		final InternalApiSecurityProperties internalApiSecurityProperties,
		final TelegramProperties telegramProperties
	) {
		return new RequestAuthenticationFilter(internalApiSecurityProperties, telegramProperties);
	}
}
