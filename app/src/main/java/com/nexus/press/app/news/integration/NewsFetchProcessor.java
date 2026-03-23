package com.nexus.press.app.news.integration;

import reactor.core.publisher.Flux;
import com.nexus.press.app.news.model.RawNews;

public interface NewsFetchProcessor {

	Flux<RawNews> fetchNews();
}
