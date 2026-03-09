package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class ReutersGoogleNewsPopulateContentProcessor implements NewsPopulateContentProcessor {

	private static final Set<Media> SUPPORTED_MEDIA = Set.of(Media.REUTERS);

	private static final List<String> REUTERS_SELECTORS = List.of(
		"[data-testid=paragraph] p",
		"[data-testid=Body] p",
		"article p",
		"[itemprop=articleBody] p",
		"main p"
	);

	private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

	private final WebClient webClient;

	public ReutersGoogleNewsPopulateContentProcessor(final WebClientConfig webClientConfig) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.NEWS);
	}

	@Override
	public Set<Media> getSupportedMedia() {
		return SUPPORTED_MEDIA;
	}

	@Override
	public int getPriority() {
		return 260;
	}

	@Override
	public Mono<RawNews> process(final RawNews news) {
		log.info("Получение содержания новости REUTERS (google resolver): id={} title={}", news.getId(), news.getTitle());

		return fetchHtml(news.getLink())
			.flatMap(googleHtml -> {
				final var targetUrl = resolveTargetUrl(news.getLink(), googleHtml);
				if (!StringUtils.hasText(targetUrl)) {
					return Mono.just(news.withRawContent(HtmlContentSupport.fallbackFromDescription(news)));
				}
				return fetchHtml(targetUrl)
					.map(targetHtml -> HtmlContentSupport.extractArticleText(targetHtml, REUTERS_SELECTORS))
					.map(text -> text.isBlank() ? HtmlContentSupport.fallbackFromDescription(news) : text)
					.map(news::withRawContent);
			})
			.onErrorResume(ex -> {
				log.warn("Сбой REUTERS parser id={}: {}", news.getId(), ex.getMessage());
				return Mono.just(news.withRawContent(HtmlContentSupport.fallbackFromDescription(news)));
			});
	}

	private Mono<String> fetchHtml(final String url) {
		return webClient.get()
			.uri(url)
			.headers(HtmlContentSupport::applyBrowserHeaders)
			.retrieve()
			.bodyToMono(String.class);
	}

	private String resolveTargetUrl(final String sourceUrl, final String html) {
		if (!StringUtils.hasText(html)) return "";

		final var doc = Jsoup.parse(html, sourceUrl);
		final var links = doc.select("a[href]").stream()
			.map(link -> toAbsoluteUrl(sourceUrl, link.attr("href")))
			.map(this::unwrapGoogleRedirect)
			.filter(StringUtils::hasText)
			.toList();

		for (final var url : links) {
			if (isReutersUrl(url)) return url;
		}
		for (final var url : links) {
			if (isExternalUrl(url)) return url;
		}

		final var matcher = URL_PATTERN.matcher(html);
		while (matcher.find()) {
			final var candidate = unwrapGoogleRedirect(matcher.group());
			if (isReutersUrl(candidate)) return candidate;
		}
		return "";
	}

	private String unwrapGoogleRedirect(final String rawUrl) {
		if (!StringUtils.hasText(rawUrl)) return "";
		final var candidate = decode(rawUrl);
		try {
			final var uri = URI.create(candidate);
			final var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			if (!host.contains("google.")) return candidate;

			final var query = uri.getRawQuery();
			if (!StringUtils.hasText(query)) return candidate;

			for (final var token : query.split("&")) {
				final int idx = token.indexOf('=');
				if (idx <= 0 || idx == token.length() - 1) continue;
				final var key = decode(token.substring(0, idx));
				if (!"url".equals(key) && !"q".equals(key) && !"continue".equals(key)) continue;
				final var value = decode(token.substring(idx + 1));
				if (isReutersUrl(value) || isExternalUrl(value)) return value;
			}
			return candidate;
		} catch (final Exception e) {
			return candidate;
		}
	}

	private String toAbsoluteUrl(final String baseUrl, final String href) {
		if (!StringUtils.hasText(href)) return "";
		final var value = href.trim();
		try {
			final var base = URI.create(baseUrl);
			return base.resolve(value).toString();
		} catch (final Exception e) {
			return value;
		}
	}

	private boolean isReutersUrl(final String url) {
		if (!StringUtils.hasText(url)) return false;
		try {
			final var host = URI.create(url).getHost();
			return host != null && host.toLowerCase().contains("reuters.com");
		} catch (final Exception e) {
			return false;
		}
	}

	private boolean isExternalUrl(final String url) {
		if (!StringUtils.hasText(url)) return false;
		if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
		try {
			final var host = URI.create(url).getHost();
			if (host == null) return false;
			final var lower = host.toLowerCase();
			return !lower.contains("google.");
		} catch (final Exception e) {
			return false;
		}
	}

	private String decode(final String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
