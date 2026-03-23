package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import com.nexus.press.app.news.model.SimilarNewsItem;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FindSimilarNews {

	private final NewsSimilarityRepository newsSimilarityRepository;

	public Flux<SimilarNewsItem> execute(final String id, final int topN, final double minScore) {
		return newsSimilarityRepository.topSimilar(id, topN, minScore)
			.map(item -> new SimilarNewsItem(item.id(), item.score()));
	}
}
