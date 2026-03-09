package com.nexus.press.app.config.property;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "press.analytics.product-report")
public class ProductReportProperties {

	private boolean enabled = true;
	private Duration interval = Duration.ofHours(24);
}
