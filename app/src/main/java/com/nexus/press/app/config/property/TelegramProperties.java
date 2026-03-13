package com.nexus.press.app.config.property;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.telegram")
public record TelegramProperties(
	HttpClientProperties.ClientConfig http,
	Bot bot,
	Delivery delivery,
	Webhook webhook
) {

	public TelegramProperties {
		bot = bot == null ? new Bot("") : bot;
		delivery = delivery == null ? Delivery.defaults() : delivery.withDefaults();
		webhook = webhook == null ? new Webhook("") : webhook;
	}

	public record Bot(
		String token
	) {

		public Bot {
			token = token == null ? "" : token;
		}
	}

	public record Delivery(
		boolean enabled,
		List<String> chatIds,
		Duration interval,
		Duration lookback,
		Integer maxItems,
		String language
	) {

		private static Delivery defaults() {
			return new Delivery(false, List.of(), Duration.ofHours(24), Duration.ofHours(24), 7, "ru");
		}

		public Delivery {
			chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
			interval = interval == null ? Duration.ofHours(24) : interval;
			lookback = lookback == null ? Duration.ofHours(24) : lookback;
			maxItems = maxItems == null ? 7 : maxItems;
			language = language == null ? "ru" : language;
		}

		private Delivery withDefaults() {
			return new Delivery(enabled, chatIds, interval, lookback, maxItems, language);
		}
	}

	public record Webhook(
		String secretToken
	) {

		public Webhook {
			secretToken = secretToken == null ? "" : secretToken;
		}
	}
}
