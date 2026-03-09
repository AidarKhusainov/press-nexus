package com.nexus.press.app.service.news.platform;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "live.tests", matches = "true")
class AllSourcesLiveParsingIntegrationTest {

	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(40);
	private static final Duration CONTENT_TIMEOUT = Duration.ofSeconds(40);
	private static final int MIN_CONTENT_LENGTH = 120;
	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());
	private static final WebClientConfig WEB_CLIENT_CONFIG = createWebClientConfig();
	private static final PopularRssFetchProcessor FETCH_PROCESSOR = new PopularRssFetchProcessor(WEB_CLIENT_CONFIG);
	private static final List<NewsPopulateContentProcessor> CONTENT_PROCESSORS = createContentProcessors();
	// Example: -Dlive.tests.disabledSources=BBC,TASS
	private static final Set<Media> DISABLED_SOURCES = parseMediaSet("live.tests.disabledSources");
	// Example: -Dlive.tests.onlySources=BBC
	private static final Set<Media> ONLY_SOURCES = parseMediaSet("live.tests.onlySources");

	@ParameterizedTest(name = "{0}")
	@MethodSource("sources")
	void sourceShouldParseRssAndFullContent(final PopularRssFetchProcessor.FeedDefinition source) {
		if (!ONLY_SOURCES.isEmpty()) {
			Assumptions.assumeTrue(ONLY_SOURCES.contains(source.media()),
				() -> "Пропуск " + source.media() + ": источник не входит в live.tests.onlySources");
		}
		Assumptions.assumeFalse(DISABLED_SOURCES.contains(source.media()),
			() -> "Пропуск " + source.media() + ": источник отключен в live.tests.disabledSources");

		final var raw = FETCH_PROCESSOR.fetchFeed(source)
			.next()
			.block(FETCH_TIMEOUT);

		assertNotNull(raw, () -> source.media() + ": RSS не вернул ни одной новости (" + source.feedUrl() + ")");
		assertTrue(StringUtils.hasText(raw.getId()), () -> source.media() + ": пустой id");
		assertTrue(StringUtils.hasText(raw.getLink()), () -> source.media() + ": пустой link");
		assertTrue(StringUtils.hasText(raw.getTitle()), () -> source.media() + ": пустой title");

		final var contentProcessor = resolveContentProcessor(raw.getSource());
		assertNotNull(contentProcessor, () -> source.media() + ": не найден content processor");

		final var withContent = contentProcessor.process(raw).block(CONTENT_TIMEOUT);
		assertNotNull(withContent, () -> source.media() + ": не удалось получить полный контент");
		final var fullContent = normalize(withContent.getRawContent());
		final var fallback = normalize(HtmlContentSupport.fallbackFromDescription(raw));
		assertTrue(StringUtils.hasText(fullContent),
			() -> source.media() + ": пустой rawContent для " + raw.getLink());
		assertTrue(fullContent.length() >= MIN_CONTENT_LENGTH,
			() -> source.media() + ": слишком короткий контент (" + fullContent.length()
				+ "), возможно сломан парсинг страницы: " + raw.getLink());
		assertNotEquals(fallback, fullContent,
			() -> source.media() + ": вернулся fallback из title/description вместо полного текста: " + raw.getLink());
	}

	private static Stream<Arguments> sources() {
		return PopularRssFetchProcessor.feedDefinitions().stream()
			.map(Arguments::of);
	}

	private static List<NewsPopulateContentProcessor> createContentProcessors() {
		return List.<NewsPopulateContentProcessor>of(
			new ReutersGoogleNewsPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new TassPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new RossiyskayaGazetaPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new GenericPopulateContentProcessor(WEB_CLIENT_CONFIG)
		).stream()
			.sorted(Comparator.comparingInt(NewsPopulateContentProcessor::getPriority).reversed())
			.toList();
	}

	private static NewsPopulateContentProcessor resolveContentProcessor(final Media media) {
		for (final var processor : CONTENT_PROCESSORS) {
			if (processor.getSupportedMedia().contains(media)) return processor;
		}
		return null;
	}

	private static Set<Media> parseMediaSet(final String propertyName) {
		final var value = System.getProperty(propertyName, "");
		if (!StringUtils.hasText(value)) return Set.of();
		final var parsed = new java.util.LinkedHashSet<Media>();
		for (final var token : value.split("[,;\\s]+")) {
			if (!StringUtils.hasText(token)) continue;
			try {
				parsed.add(Media.valueOf(token.trim().toUpperCase(Locale.ROOT)));
			} catch (final IllegalArgumentException ex) {
				throw new IllegalArgumentException(
					"Некорректное значение в " + propertyName + ": " + token.trim() + ". Ожидается имя Media enum.", ex);
			}
		}
		return Set.copyOf(parsed);
	}

	private static String normalize(final String value) {
		if (!StringUtils.hasText(value)) return "";
		return value.replace('\u00A0', ' ')
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.replaceAll("\n{3,}", "\n\n")
			.strip();
	}

	private static WebClientConfig createWebClientConfig() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"https://example.com",
			new HttpClientProperties.Timeout(Duration.ofSeconds(20), Duration.ofSeconds(20)),
			new HttpClientProperties.Retry(1, Duration.ofSeconds(1), 0.2)
		);
		final var props = new HttpClientProperties(Map.of(HttpClientName.NEWS, cfg));
		return new WebClientConfig(props, APP_METRICS);
	}
}
