package com.nexus.press.app.config.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "press.security.telegram-webhook")
public class TelegramWebhookSecurityProperties {

	private String secretToken = "";
}
