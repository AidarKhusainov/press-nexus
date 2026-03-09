package com.nexus.press.app.service.news.platform;

import reactor.core.publisher.Flux;
import com.nexus.press.app.service.news.model.RawNews;

public interface NewsFetchProcessor {

	Flux<RawNews> fetchNews();
}