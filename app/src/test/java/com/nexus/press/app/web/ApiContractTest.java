package com.nexus.press.app.web;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.analytics.format.ProductReportFormatter;
import com.nexus.press.app.analytics.model.ProductDailyReport;
import com.nexus.press.app.analytics.model.PremiumIntentSegmentReport;
import com.nexus.press.app.analytics.usecase.BuildProductDailyReport;
import com.nexus.press.app.analytics.web.ProductReportController;
import com.nexus.press.app.brief.format.DailyBriefFormatter;
import com.nexus.press.app.brief.model.BriefImportance;
import com.nexus.press.app.brief.model.DailyBrief;
import com.nexus.press.app.brief.model.DailyBriefItem;
import com.nexus.press.app.brief.usecase.BuildDailyBrief;
import com.nexus.press.app.brief.web.BriefController;
import com.nexus.press.app.config.RequestAuthenticationConfiguration;
import com.nexus.press.app.config.property.SimilarityProperties;
import com.nexus.press.app.feedback.model.FeedbackEventType;
import com.nexus.press.app.feedback.usecase.RecordTelegramFeedback;
import com.nexus.press.app.feedback.web.FeedbackController;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsItem;
import com.nexus.press.app.news.usecase.BuildNewsClusters;
import com.nexus.press.app.news.usecase.FindNewsCluster;
import com.nexus.press.app.news.usecase.FindSimilarNews;
import com.nexus.press.app.news.web.SimilarityController;
import com.nexus.press.app.telegram.usecase.DeliverDailyBriefToTelegramUsers;
import com.nexus.press.app.telegram.usecase.HandleTelegramUpdate;
import com.nexus.press.app.telegram.web.TelegramWebhookController;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
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
class ApiContractTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BuildDailyBrief buildDailyBrief;
	@MockBean
	private DailyBriefFormatter dailyBriefFormatter;
	@MockBean
	private DeliverDailyBriefToTelegramUsers deliverDailyBriefToTelegramUsers;
	@MockBean
	private RecordTelegramFeedback recordTelegramFeedback;
	@MockBean
	private BuildProductDailyReport buildProductDailyReport;
	@MockBean
	private ProductReportFormatter productReportFormatter;
	@MockBean
	private BuildNewsClusters buildNewsClusters;
	@MockBean
	private SimilarityProperties similarityProperties;
	@MockBean
	private FindNewsCluster findNewsCluster;
	@MockBean
	private FindSimilarNews findSimilarNews;
	@MockBean
	private HandleTelegramUpdate handleTelegramUpdate;

	@Test
	void dailyBriefJsonContract() {
		given(buildDailyBrief.execute(any(BuildDailyBrief.Request.class))).willReturn(Mono.just(sampleBrief()));

		webTestClient.get()
			.uri("/api/brief/daily?hours=24&limit=2&lang=ru")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.language").isEqualTo("ru")
			.jsonPath("$.items[0].newsId").isEqualTo("news-1")
			.jsonPath("$.items[0].importance").isEqualTo("MUST_KNOW");
	}

	@Test
	void dailyBriefTextContract() {
		given(buildDailyBrief.execute(any(BuildDailyBrief.Request.class))).willReturn(Mono.just(sampleBrief()));
		given(dailyBriefFormatter.toTelegramMessage(any(DailyBrief.class))).willReturn("Daily brief text");

		webTestClient.get()
			.uri("/api/brief/daily/text")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.expectBody(String.class).isEqualTo("Daily brief text");
	}

	@Test
	void sendDailyBriefContract() {
		given(deliverDailyBriefToTelegramUsers.execute()).willReturn(Mono.just(3));

		webTestClient.post()
			.uri("/api/brief/daily/send")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.sentChats").isEqualTo(3);
	}

	@Test
	void feedbackContract() {
		given(recordTelegramFeedback.execute(
			anyString(),
			any(FeedbackEventType.class),
			any(),
			any(),
			anyMap()
		))
			.willReturn(Mono.empty());

		webTestClient.post()
			.uri("/api/feedback")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("chatId", "123", "eventType", "useful", "newsId", "news-1"))
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.ok").isEqualTo(true);
	}

	@Test
	void feedbackInvalidContract() {
		webTestClient.post()
			.uri("/api/feedback")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("chatId", "123", "eventType", "unsupported"))
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.ok").isEqualTo(false)
			.jsonPath("$.error").isEqualTo("invalid_event_type");
	}

	@Test
	void productReportContract() {
		given(buildProductDailyReport.execute(any())).willReturn(Mono.just(sampleProductReport()));
		given(productReportFormatter.toText(any(ProductDailyReport.class))).willReturn("report text");

		webTestClient.get()
			.uri("/api/analytics/product-report/daily")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.reportDate").isEqualTo("2026-03-09")
			.jsonPath("$.d1RetentionPct").isEqualTo(40.0)
			.jsonPath("$.premiumIntentSegments[0].segment").isEqualTo("economy")
			.jsonPath("$.premiumIntentSegments[0].intentPct").isEqualTo(20.0);

		webTestClient.get()
			.uri("/api/analytics/product-report/daily/text")
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.expectBody(String.class).isEqualTo("report text");
	}

	@Test
	void similarityAndClusterContracts() {
		given(similarityProperties.getTopN()).willReturn(5);
		given(similarityProperties.getMinScore()).willReturn(0.6);
		given(similarityProperties.getClusterMinScore()).willReturn(0.7);
		given(findSimilarNews.execute(anyString(), anyInt(), anyDouble()))
			.willReturn(Flux.just(new SimilarNewsItem("n2", 0.92)));

		final var cluster = new NewsCluster(Set.of("n1", "n2"), "n1");
		given(buildNewsClusters.execute(anyDouble())).willReturn(Mono.just(List.of(cluster)));
		given(findNewsCluster.execute(anyString(), anyDouble())).willReturn(Mono.just(cluster));

		webTestClient.get()
			.uri("/api/news/n1/similar")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].id").isEqualTo("n2")
			.jsonPath("$[0].score").isEqualTo(0.92);

		webTestClient.get()
			.uri("/api/clusters")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].representativeId").isEqualTo("n1");

		webTestClient.get()
			.uri("/api/news/n1/cluster")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.representativeId").isEqualTo("n1");
	}

	@Test
	void telegramWebhookContract() {
		given(handleTelegramUpdate.execute(any(Map.class))).willReturn(Mono.empty());

		webTestClient.post()
			.uri("/api/telegram/webhook")
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

	private ProductDailyReport sampleProductReport() {
		return new ProductDailyReport(
			LocalDate.of(2026, 3, 9),
			OffsetDateTime.parse("2026-03-08T00:00:00Z"),
			OffsetDateTime.parse("2026-03-09T00:00:00Z"),
			100,
			50,
			40,
			35,
			10,
			5,
			70.0,
			20.0,
			40.0,
			12,
			10,
			10.0,
			20,
			8,
			40.0,
			10,
			4,
			40.0,
			List.of(
				new PremiumIntentSegmentReport("economy", 50, 10, 12, 20.0),
				new PremiumIntentSegmentReport("news", 50, 0, 0, 0.0)
			)
		);
	}
}
