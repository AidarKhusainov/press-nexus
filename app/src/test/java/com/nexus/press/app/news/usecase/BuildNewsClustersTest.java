package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Set;
import com.nexus.press.app.news.model.NewsCluster;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildNewsClustersTest {

	@Test
	void executeBuildsConnectedComponentsAndChoosesRepresentative() {
		final NewsSimilarityRepository similarityStore = mock(NewsSimilarityRepository.class);
		final BuildNewsClusters useCase = new BuildNewsClusters(similarityStore);

		when(similarityStore.allIds()).thenReturn(Flux.just("n1", "n2", "n3"));
		when(similarityStore.neighbors("n1", 0.7)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n2", 0.95)
		));
		when(similarityStore.neighbors("n2", 0.7)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n1", 0.95)
		));
		when(similarityStore.neighbors("n3", 0.7)).thenReturn(Flux.empty());

		final List<NewsCluster> clusters = useCase.execute(0.7).block();

		assertEquals(List.of(
			new NewsCluster(Set.of("n1", "n2"), "n1"),
			new NewsCluster(Set.of("n3"), "n3")
		), clusters);
	}
}
