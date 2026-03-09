package com.nexus.press.app.config.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "press.security.internal-api")
public class InternalApiSecurityProperties {

	private boolean enabled = false;
	private String apiKey = "";
}
