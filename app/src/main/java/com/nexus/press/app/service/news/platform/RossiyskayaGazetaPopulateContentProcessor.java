package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Mono;
import java.util.List;
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
public class RossiyskayaGazetaPopulateContentProcessor implements NewsPopulateContentProcessor {

	private static final List<String> RG_SELECTORS = List.of(
		"[class*=b-material-wrapper__text] p",
		"[class*=b-material-text] p",
		"[class*=article__text] p",
		"[itemprop=articleBody] p",
		"article p",
		"main p"
	);

	private static final Set<Media> SUPPORTED_MEDIA = Set.of(Media.ROSSIYSKAYA_GAZETA);

	private final WebClient webClient;

	public RossiyskayaGazetaPopulateContentProcessor(final WebClientConfig webClientConfig) {
		this.webClient = webClientConfig.getWebClient(HttpClientName.NEWS);
	}

	@Override
	public Set<Media> getSupportedMedia() {
		return SUPPORTED_MEDIA;
	}

	@Override
	public int getPriority() {
		return 240;
	}

	@Override
	public Mono<RawNews> process(final RawNews news) {
		log.info("Получение содержания новости ROSSIYSKAYA_GAZETA (specialized): id={} title={}",
			news.getId(), news.getTitle());
		return webClient.get()
			.uri(news.getLink())
			.headers(HtmlContentSupport::applyBrowserHeaders)
			.retrieve()
			.bodyToMono(String.class)
			.map(html -> HtmlContentSupport.extractArticleText(html, RG_SELECTORS))
			.map(text -> text.isBlank() ? HtmlContentSupport.fallbackFromDescription(news) : text)
			.map(news::withRawContent)
			.onErrorResume(ex -> {
				log.warn("Сбой ROSSIYSKAYA_GAZETA parser id={}: {}", news.getId(), ex.getMessage());
				return Mono.just(news.withRawContent(HtmlContentSupport.fallbackFromDescription(news)));
			});
	}
}
