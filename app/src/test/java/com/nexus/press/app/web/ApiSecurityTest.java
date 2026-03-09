package com.nexus.press.app.web;

import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.RequestAuthenticationConfiguration;
import com.nexus.press.app.config.RequestAuthenticationFilter;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.controller.BriefController;
import com.nexus.press.app.controller.FeedbackController;
import com.nexus.press.app.controller.ProductReportController;
import com.nexus.press.app.controller.SimilarityController;
import com.nexus.press.app.controller.TelegramWebhookController;
import com.nexus.press.app.service.analytics.ProductReportService;
import com.nexus.press.app.service.brief.DailyBriefFormatter;
import com.nexus.press.app.service.brief.DailyBriefService;
import com.nexus.press.app.service.brief.model.BriefImportance;
import com.nexus.press.app.service.brief.model.DailyBrief;
import com.nexus.press.app.service.brief.model.DailyBriefItem;
import com.nexus.press.app.service.delivery.DailyBriefDeliveryService;
import com.nexus.press.app.service.feedback.FeedbackEventService;
import com.nexus.press.app.service.news.NewsClusteringService;
import com.nexus.press.app.service.news.ReactiveNewsSimilarityStore;
import com.nexus.press.app.service.profile.TelegramOnboardingBotService;
import com.nexus.press.app.web.generated.api.BriefApiController;
import com.nexus.press.app.web.generated.api.FeedbackApiController;
import com.nexus.press.app.web.generated.api.ProductReportApiController;
import com.nexus.press.app.web.generated.api.SimilarityApiController;
import com.nexus.press.app.web.generated.api.TelegramWebhookApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@WebFluxTest(controllers = {
	BriefApiController.class,
	FeedbackApiController.class,
	ProductReportApiController.class,
	SimilarityApiController.class,
	TelegramWebhookApiController.class
})
@Import({
	BriefController.class,
	FeedbackController.class,
	ProductReportController.class,
	SimilarityController.class,
	TelegramWebhookController.class,
	RequestAuthenticationConfiguration.class
})
@TestPropertySource(properties = {
	"press.security.internal-api.enabled=true",
	"press.security.internal-api.api-key=test-internal-key",
	"press.security.telegram-webhook.secret-token=test-telegram-secret"
})
class ApiSecurityTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private DailyBriefService dailyBriefService;
	@MockBean
	private DailyBriefFormatter dailyBriefFormatter;
	@MockBean
	private DailyBriefDeliveryService dailyBriefDeliveryService;
	@MockBean
	private FeedbackEventService feedbackEventService;
	@MockBean
	private ProductReportService productReportService;
	@MockBean
	private NewsClusteringService newsClusteringService;
	@MockBean
	private SimilarityProperties similarityProperties;
	@MockBean
	private ReactiveNewsSimilarityStore reactiveNewsSimilarityStore;
	@MockBean
	private TelegramOnboardingBotService telegramOnboardingBotService;

	@Test
	void publicBriefEndpointRemainsAccessibleWithoutApiKey() {
		given(dailyBriefService.buildBrief(any(), anyInt(), anyString())).willReturn(Mono.just(sampleBrief()));

		webTestClient.get()
			.uri("/api/brief/daily")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
	}

	@Test
	void protectedSendEndpointRejectsMissingApiKey() {
		webTestClient.post()
			.uri("/api/brief/daily/send")
			.exchange()
			.expectStatus().isUnauthorized()
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.error").isEqualTo("unauthorized");
	}

	@Test
	void protectedSendEndpointAcceptsValidApiKey() {
		given(dailyBriefDeliveryService.deliverNow()).willReturn(Mono.just(2));

		webTestClient.post()
			.uri("/api/brief/daily/send")
			.header(RequestAuthenticationFilter.INTERNAL_API_KEY_HEADER, "test-internal-key")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.sentChats").isEqualTo(2);
	}

	@Test
	void protectedProductReportRejectsMissingApiKey() {
		webTestClient.get()
			.uri("/api/analytics/product-report/daily")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void protectedSimilarityRejectsMissingApiKey() {
		webTestClient.get()
			.uri("/api/news/news-1/similar")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void telegramWebhookRejectsMissingSecret() {
		webTestClient.post()
			.uri("/api/telegram/webhook")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("update_id", 1001))
			.exchange()
			.expectStatus().isUnauthorized()
			.expectBody()
			.jsonPath("$.error").isEqualTo("invalid_webhook_secret");
	}

	@Test
	void telegramWebhookAcceptsValidSecret() {
		given(telegramOnboardingBotService.handleUpdate(any(Map.class))).willReturn(Mono.empty());

		webTestClient.post()
			.uri("/api/telegram/webhook")
			.header(RequestAuthenticationFilter.TELEGRAM_SECRET_HEADER, "test-telegram-secret")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("update_id", 1001))
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.ok").isEqualTo(true);
	}

	private DailyBrief sampleBrief() {
		return new DailyBrief(
			OffsetDateTime.parse("2026-03-09T10:00:00Z"),
			OffsetDateTime.parse("2026-03-08T10:00:00Z"),
			OffsetDateTime.parse("2026-03-09T10:00:00Z"),
			"ru",
			List.of(new DailyBriefItem(
				"news-1",
				"Title",
				"https://example.com/news-1",
				"BBC",
				OffsetDateTime.parse("2026-03-09T09:00:00Z"),
				BriefImportance.MUST_KNOW,
				"What happened",
				"Why important",
				"What next"
			))
		);
	}
}
