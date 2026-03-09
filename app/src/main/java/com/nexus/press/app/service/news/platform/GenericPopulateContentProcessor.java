package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Mono;
import java.util.EnumSet;
import java.util.Set;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class GenericPopulateContentProcessor implements NewsPopulateContentProcessor {

	private static final Set<Media> SUPPORTED_MEDIA = Set.copyOf(
		EnumSet.allOf(Media.class)
	);

	private final WebClient webClient;

	public GenericPopulateContentProcessor(final WebClientConfig webClientConfig) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.NEWS);
	}

	@Override
	public Set<Media> getSupportedMedia() {
		return SUPPORTED_MEDIA;
	}

	@Override
	public boolean supports(final RawNews news) {
		return true;
	}

	@Override
	public int getPriority() {
		return -100;
	}

	@Override
	public Mono<RawNews> process(final RawNews news) {
		log.info("Получение содержания новости {}: id={} title={}", news.getSource(), news.getId(), news.getTitle());

		return webClient.get()
			.uri(news.getLink())
			.headers(HtmlContentSupport::applyBrowserHeaders)
			.retrieve()
			.bodyToMono(String.class)
			.map(html -> HtmlContentSupport.extractArticleText(html, HtmlContentSupport.GENERIC_ARTICLE_SELECTORS))
			.map(text -> text.isBlank() ? HtmlContentSupport.fallbackFromDescription(news) : text)
			.map(news::withRawContent)
			.onErrorResume(ex -> {
				log.warn("Сбой при получении полного текста {} id={}: {}",
					news.getSource(), news.getId(), ex.getMessage());
				return Mono.just(news.withRawContent(HtmlContentSupport.fallbackFromDescription(news)));
			});
	}
}
