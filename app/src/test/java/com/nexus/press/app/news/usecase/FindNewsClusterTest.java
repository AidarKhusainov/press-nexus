package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindNewsClusterTest {

	@Test
	void executeReturnsClusterForRequestedNewsId() {
		final NewsSimilarityRepository similarityStore = mock(NewsSimilarityRepository.class);
		final FindNewsCluster useCase = new FindNewsCluster(similarityStore);

		when(similarityStore.allIds()).thenReturn(Flux.just("n1", "n2", "n3"));
		when(similarityStore.neighbors("n1", 0.7)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n2", 0.95)
		));
		when(similarityStore.neighbors("n2", 0.7)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n1", 0.95)
		));
		when(similarityStore.neighbors("n3", 0.7)).thenReturn(Flux.empty());

		final NewsCluster cluster = useCase.execute("n2", 0.7).block();

		assertEquals(new NewsCluster(java.util.Set.of("n1", "n2"), "n1"), cluster);
	}
}
