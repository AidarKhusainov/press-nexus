package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Flux;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopularRssFetchProcessorTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void parseFeedParsesRssItem() throws Exception {
		final var xml = """
			<rss version="2.0">
			  <channel>
			    <item>
			      <guid>bbc-1</guid>
			      <title>  Title&#160;One </title>
			      <link>https://example.com/1</link>
			      <description><![CDATA[<p>Hello <b>world</b>.</p>]]></description>
			      <pubDate>Mon, 06 Jan 2025 10:15:30 +0000</pubDate>
			      <language>en-GB</language>
			    </item>
			  </channel>
			</rss>
			""";

		final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
		final var parsed = parseFeed(processor, xml, Media.BBC, "https://feed.example/rss", "en");

		assertEquals(1, parsed.size());
		final var news = parsed.getFirst();
		assertEquals("bbc-1", news.getId());
		assertEquals("https://example.com/1", news.getLink());
		assertEquals("Title One", news.getTitle());
		assertEquals("Hello world.", news.getDescription());
		assertEquals(Media.BBC, news.getSource());
		assertEquals("en-GB", news.getLanguage());
		assertNotNull(news.getPublishedDate());
	}

	@Test
	void parseFeedParsesAtomEntryAndUsesHrefAndDateFormats() throws Exception {
		final var xml = """
			<feed xmlns="http://www.w3.org/2005/Atom">
			  <entry>
			    <title>World update</title>
			    <link href="https://example.com/atom-1"/>
			    <summary>  latest updates </summary>
			    <published>2026-01-02T03:04:05+0000</published>
			  </entry>
			</feed>
			""";

		final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
		final var parsed = parseFeed(processor, xml, Media.REUTERS, "https://feed.example/atom", "en");

		assertEquals(1, parsed.size());
		final var news = parsed.getFirst();
		assertEquals("https://example.com/atom-1", news.getId());
		assertEquals("https://example.com/atom-1", news.getLink());
		assertEquals("World update", news.getTitle());
		assertEquals("latest updates", news.getDescription());
		assertEquals(OffsetDateTime.parse("2026-01-02T03:04:05Z"), news.getPublishedDate());
		assertEquals("en", news.getLanguage());
	}

	@Test
	void parseFeedSkipsItemsWithoutLink() throws Exception {
		final var xml = """
			<rss version="2.0">
			  <channel>
			    <item>
			      <guid>x</guid>
			      <title>No link item</title>
			      <description>desc</description>
			    </item>
			  </channel>
			</rss>
			""";

		final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
		final var parsed = parseFeed(processor, xml, Media.CNN, "https://feed.example/rss", "en");

		assertTrue(parsed.isEmpty());
	}

	@Test
	void parseFeedUsesFallbackIdWhenGuidMissing() throws Exception {
		final var xml = """
			<rss version="2.0">
			  <channel>
			    <item>
			      <title>Only link id</title>
			      <link>https://example.com/no-guid</link>
			      <description>desc</description>
			    </item>
			  </channel>
			</rss>
			""";

		final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
		final var parsed = parseFeed(processor, xml, Media.NPR, "https://feed.example/rss", "en");

		assertEquals(1, parsed.size());
		assertEquals("https://example.com/no-guid", parsed.getFirst().getId());
		assertFalse(parsed.getFirst().getId().isBlank());
		assertNotEquals(" ", parsed.getFirst().getId());
	}

	@Test
	void buildGoogleNewsFallbackUrlsShouldEncodeQueriesAndIncludeQuotedVariant() throws Exception {
		final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
		final var feed = newFeedDefinition(Media.SIB_FM, "https://sib.fm/rss", "ru");

		final Method method = PopularRssFetchProcessor.class
			.getDeclaredMethod("buildGoogleNewsFallbackUrls", feed.getClass());
		method.setAccessible(true);

		@SuppressWarnings("unchecked")
		final var urls = (List<String>) method.invoke(processor, feed);

		assertTrue(urls.stream().anyMatch(u -> u.contains("q=site%3Asib.fm")));
		assertTrue(urls.stream().anyMatch(u -> u.contains("q=sib.fm")));
		assertTrue(urls.stream().anyMatch(u -> u.contains("q=%22sib.fm%22")));
	}

	@Test
	void fetchFeedShouldContinueWhenFirstCandidateIsEmpty() throws Exception {
		final HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(0), 0);
		} catch (final IOException | RuntimeException ex) {
			Assumptions.assumeTrue(false, "Sandbox does not allow opening local socket: " + ex.getMessage());
			return;
		}
		server.createContext("/rss", exchange -> {
			final var body = "<rss version=\"2.0\"><channel></channel></rss>";
			exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=UTF-8");
			final var bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.createContext("/rss.xml", exchange -> {
			final var body = """
				<rss version="2.0">
				  <channel>
				    <item>
				      <guid>ok-1</guid>
				      <title>Fallback candidate works</title>
				      <link>https://example.com/ok</link>
				      <description>ok</description>
				      <pubDate>Mon, 06 Jan 2025 10:15:30 +0000</pubDate>
				    </item>
				  </channel>
				</rss>
				""";
			exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=UTF-8");
			final var bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();

		try {
			final int port = server.getAddress().getPort();
			final var processor = new PopularRssFetchProcessor(webClientConfig(), newsPipelineProperties());
			final var feed = newFeedDefinition(Media.SIB_FM, "http://localhost:" + port + "/rss", "ru");
			final var parsed = fetchFeed(processor, feed).collectList().block(Duration.ofSeconds(20));

			assertNotNull(parsed);
			assertEquals(1, parsed.size());
			assertEquals("ok-1", parsed.getFirst().getId());
			assertEquals("https://example.com/ok", parsed.getFirst().getLink());
		} finally {
			server.stop(0);
		}
	}

	private static WebClientConfig webClientConfig() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"http://localhost",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		final var props = new HttpClientProperties(Map.of(HttpClientName.NEWS, cfg));
		return new WebClientConfig(props, APP_METRICS);
	}

	private static NewsPipelineProperties newsPipelineProperties() {
		final var properties = new NewsPipelineProperties();
		properties.setFetchSourceConcurrency(2);
		return properties;
	}

	@SuppressWarnings("unchecked")
	private static List<RawNews> parseFeed(
		final PopularRssFetchProcessor processor,
		final String xml,
		final Media media,
		final String feedUrl,
		final String language
	) throws Exception {
		Class<?> feedClass = null;
		for (final var nested : PopularRssFetchProcessor.class.getDeclaredClasses()) {
			if ("FeedDefinition".equals(nested.getSimpleName())) {
				feedClass = nested;
				break;
			}
		}
		if (feedClass == null) {
			throw new IllegalStateException("FeedDefinition class not found");
		}
		final Constructor<?> feedConstructor = feedClass.getDeclaredConstructor(Media.class, String.class, String.class);
		feedConstructor.setAccessible(true);
		final Object feed = feedConstructor.newInstance(media, feedUrl, language);

		final Method parseFeed = PopularRssFetchProcessor.class.getDeclaredMethod("parseFeed", String.class, feedClass);
		parseFeed.setAccessible(true);
		return (List<RawNews>) parseFeed.invoke(processor, xml, feed);
	}

	@SuppressWarnings("unchecked")
	private static Flux<RawNews> fetchFeed(
		final PopularRssFetchProcessor processor,
		final Object feed
	) throws Exception {
		final Method fetchFeed = PopularRssFetchProcessor.class.getDeclaredMethod("fetchFeed", feed.getClass());
		fetchFeed.setAccessible(true);
		return (Flux<RawNews>) fetchFeed.invoke(processor, feed);
	}

	private static Object newFeedDefinition(final Media media, final String feedUrl, final String language) throws Exception {
		Class<?> feedClass = null;
		for (final var nested : PopularRssFetchProcessor.class.getDeclaredClasses()) {
			if ("FeedDefinition".equals(nested.getSimpleName())) {
				feedClass = nested;
				break;
			}
		}
		if (feedClass == null) {
			throw new IllegalStateException("FeedDefinition class not found");
		}
		final Constructor<?> feedConstructor = feedClass.getDeclaredConstructor(Media.class, String.class, String.class);
		feedConstructor.setAccessible(true);
		return feedConstructor.newInstance(media, feedUrl, language);
	}
}
