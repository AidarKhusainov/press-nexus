package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Mono;
import java.util.Set;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;

public interface NewsPopulateContentProcessor {

	Set<Media> getSupportedMedia();

	default boolean supports(final RawNews news) {
		return news != null
			&& news.getSource() != null
			&& getSupportedMedia().contains(news.getSource());
	}

	default int getPriority() {
		return 0;
	}

	Mono<RawNews> process(RawNews news);
}
