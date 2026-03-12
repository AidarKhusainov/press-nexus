package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.RawNews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfSystemProperty(named = "live.tests.coverage", matches = "true")
class AllSourcesCoverageLiveTest {

	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration CONTENT_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(8);
	private static final int FULL_TEXT_MIN_LEN = 280;
	private static final int CONCURRENCY = 12;
	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());
	private static final WebClientConfig WEB_CLIENT_CONFIG = createWebClientConfig();

	@Test
	void shouldReportFullTextCoverageAcrossAllSources() {
		final var fetchProcessor = new PopularRssFetchProcessor(WEB_CLIENT_CONFIG, new NewsPipelineProperties());
		final var contentProcessors = List.<NewsPopulateContentProcessor>of(
			new ReutersGoogleNewsPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new TassPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new RossiyskayaGazetaPopulateContentProcessor(WEB_CLIENT_CONFIG),
			new GenericPopulateContentProcessor(WEB_CLIENT_CONFIG)
		).stream()
			.sorted(Comparator.comparingInt(NewsPopulateContentProcessor::getPriority).reversed())
			.toList();
		final var sources = PopularRssFetchProcessor.feedDefinitions();

		final List<Result> results = Flux.fromIterable(sources)
			.flatMap(src -> evaluateSource(src, fetchProcessor, contentProcessors), CONCURRENCY)
			.collectList()
			.block(TOTAL_TIMEOUT);

		assertNotNull(results, "results must be collected");
		final long totalSources = sources.size();
		final long evaluated = results.size();
		final long rssOk = results.stream().filter(Result::rssOk).count();
		final long contentOk = results.stream().filter(Result::contentOk).count();
		final long fullTextOk = results.stream().filter(Result::fullTextOk).count();

		final double rssPct = totalSources == 0 ? 0.0 : rssOk * 100.0 / totalSources;
		final double contentPct = totalSources == 0 ? 0.0 : contentOk * 100.0 / totalSources;
		final double fullTextPct = totalSources == 0 ? 0.0 : fullTextOk * 100.0 / totalSources;

		System.out.printf(
			"FULL_TEXT_COVERAGE total_sources=%d evaluated=%d rss_ok=%d(%.2f%%) content_ok=%d(%.2f%%) full_text_ok=%d(%.2f%%)%n",
			totalSources, evaluated, rssOk, rssPct, contentOk, contentPct, fullTextOk, fullTextPct
		);

		results.stream()
			.filter(r -> !r.fullTextOk())
			.limit(30)
			.forEach(r -> System.out.printf("FULL_TEXT_MISS %s reason=%s feed=%s%n", r.media(), r.reason(), r.feedUrl()));
	}

	private Mono<Result> evaluateSource(
		final PopularRssFetchProcessor.FeedDefinition source,
		final PopularRssFetchProcessor fetchProcessor,
		final List<NewsPopulateContentProcessor> contentProcessors
	) {
		return fetchProcessor.fetchFeed(source)
			.next()
			.timeout(FETCH_TIMEOUT)
			.flatMap(raw -> {
				if (raw == null || !StringUtils.hasText(raw.getLink())) {
					return Mono.just(Result.failed(source.media().name(), source.feedUrl(), "rss-empty"));
				}
				final var contentProcessor = resolveContentProcessor(contentProcessors, raw);
				if (contentProcessor == null) {
					return Mono.just(Result.failed(source.media().name(), source.feedUrl(), "no-content-processor"));
				}
				return contentProcessor.process(raw)
					.timeout(CONTENT_TIMEOUT)
					.map(withContent -> toResult(source, raw, withContent))
					.onErrorResume(ex -> Mono.just(Result.failed(source.media().name(), source.feedUrl(), "content-error")));
			})
			.switchIfEmpty(Mono.just(Result.failed(source.media().name(), source.feedUrl(), "rss-empty")))
			.onErrorResume(ex -> Mono.just(Result.failed(source.media().name(), source.feedUrl(), "rss-error")));
	}

	private NewsPopulateContentProcessor resolveContentProcessor(
		final List<NewsPopulateContentProcessor> contentProcessors,
		final RawNews news
	) {
		for (final var processor : contentProcessors) {
			if (processor.supports(news)) return processor;
		}
		return null;
	}

	private Result toResult(final PopularRssFetchProcessor.FeedDefinition source, final RawNews raw, final RawNews withContent) {
		final var content = normalize(withContent == null ? "" : withContent.getRawContent());
		final var fallback = normalize(fallbackFrom(raw));
		final boolean rssOk = true;
		final boolean contentOk = StringUtils.hasText(content);
		final boolean fullTextOk = contentOk && !content.equals(fallback) && content.length() >= FULL_TEXT_MIN_LEN;
		final String reason = !contentOk ? "content-empty"
			: content.equals(fallback) ? "fallback"
			: content.length() < FULL_TEXT_MIN_LEN ? "too-short"
			: "ok";
		return new Result(source.media().name(), source.feedUrl(), rssOk, contentOk, fullTextOk, reason);
	}

	private String fallbackFrom(final RawNews news) {
		final var title = normalize(news.getTitle());
		final var description = normalize(news.getDescription());
		if (StringUtils.hasText(title) && StringUtils.hasText(description)) return title + "\n\n" + description;
		if (StringUtils.hasText(title)) return title;
		return description;
	}

	private String normalize(final String value) {
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

	private record Result(
		String media,
		String feedUrl,
		boolean rssOk,
		boolean contentOk,
		boolean fullTextOk,
		String reason
	) {
		static Result failed(final String media, final String feedUrl, final String reason) {
			return new Result(media, feedUrl, false, false, false, reason);
		}
	}
}
