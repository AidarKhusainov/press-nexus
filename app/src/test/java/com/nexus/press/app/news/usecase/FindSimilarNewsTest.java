package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import java.util.List;
import com.nexus.press.app.news.model.SimilarNewsItem;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindSimilarNewsTest {

	@Test
	void executeMapsStoreItemsToNewsModel() {
		final NewsSimilarityRepository similarityStore = mock(NewsSimilarityRepository.class);
		final FindSimilarNews useCase = new FindSimilarNews(similarityStore);

		when(similarityStore.topSimilar("n1", 5, 0.6)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n2", 0.92)
		));

		final List<SimilarNewsItem> result = useCase.execute("n1", 5, 0.6).collectList().block();

		assertEquals(List.of(new SimilarNewsItem("n2", 0.92)), result);
	}
}
