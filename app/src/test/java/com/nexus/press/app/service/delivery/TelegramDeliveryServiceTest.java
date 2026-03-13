package com.nexus.press.app.service.delivery;

import reactor.core.publisher.Mono;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramDeliveryServiceTest {

	@Mock
	private WebClientConfig webClientConfig;

	private TelegramDeliveryService service;

	@BeforeEach
	void setUp() {
		service = new TelegramDeliveryService(webClientConfig);
	}

	@Test
	void answerCallbackQuerySwallowsTelegramBadRequest() {
		final WebClient client = WebClient.builder()
			.baseUrl("https://api.telegram.org")
			.exchangeFunction(request -> Mono.just(
				ClientResponse.create(HttpStatus.BAD_REQUEST)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.body("""
						{"ok":false,"description":"Bad Request: query is too old and response timeout expired or query ID is invalid"}
						""")
					.build()
			))
			.build();
		when(webClientConfig.getWebClient(HttpClientName.TELEGRAM)).thenReturn(client);

		assertDoesNotThrow(() -> service.answerCallbackQuery("bot-token", "cb-id-1", "ok").block());
	}
}
