package com.nexus.press.app.config.property;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "press.delivery.telegram")
public class TelegramDeliveryProperties {

	private boolean enabled = false;
	private String botToken = "";
	private List<String> chatIds = new ArrayList<>();
	private Duration interval = Duration.ofHours(24);
	private Duration lookback = Duration.ofHours(24);
	private Integer maxItems = 7;
	private String language = "ru";
	private boolean premiumEnabled = false;
}
