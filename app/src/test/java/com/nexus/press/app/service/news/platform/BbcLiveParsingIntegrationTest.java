package com.nexus.press.app.service.news.platform;

import java.time.Duration;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "live.tests.bbc", matches = "true")
class BbcLiveParsingIntegrationTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void bbcRssAndFullContentShouldBeParsed() throws Exception {
		final var webClientConfig = realWebClientConfig();
		final var fetchProcessor = new PopularRssFetchProcessor(webClientConfig, new NewsPipelineProperties());
		final var contentProcessor = new GenericPopulateContentProcessor(webClientConfig);

		final var fromFeed = fetchProcessor.fetchFeed(
			new PopularRssFetchProcessor.FeedDefinition(Media.BBC, "https://feeds.bbci.co.uk/news/world/rss.xml", "en"))
			.next()
			.block(Duration.ofSeconds(45));

		assertNotNull(fromFeed, "Не удалось получить ни одной новости из RSS BBC");
		assertEquals(Media.BBC, fromFeed.getSource());
		assertTrue(StringUtils.hasText(fromFeed.getId()), "В новости из RSS отсутствует id");
		assertTrue(StringUtils.hasText(fromFeed.getLink()), "В новости из RSS отсутствует link");
		assertTrue(StringUtils.hasText(fromFeed.getTitle()), "В новости из RSS отсутствует title");

		final var withFullContent = contentProcessor.process(fromFeed)
			.block(Duration.ofSeconds(45));

		assertNotNull(withFullContent, "Не удалось получить полный контент для новости BBC");
		assertTrue(StringUtils.hasText(withFullContent.getRawContent()), "Полный контент пустой");
		assertTrue(withFullContent.getRawContent().length() >= 200,
			"Контент слишком короткий, вероятно парсинг статьи сломался");

		if (StringUtils.hasText(fromFeed.getDescription())) {
			assertNotEquals(fromFeed.getDescription().strip(), withFullContent.getRawContent().strip(),
				"Вернулся только description fallback, а не полный текст статьи");
		}
	}

	private static WebClientConfig realWebClientConfig() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"https://example.com",
			new HttpClientProperties.Timeout(Duration.ofSeconds(20), Duration.ofSeconds(20)),
			new HttpClientProperties.Retry(2, Duration.ofSeconds(1), 0.3)
		);
		final var props = new HttpClientProperties(Map.of(HttpClientName.NEWS, cfg));
		return new WebClientConfig(props, APP_METRICS);
	}
}
