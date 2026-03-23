package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import java.util.Map;
import java.util.Set;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindNearDuplicateNewsIdsTest {

	@Test
	void executeBuildsSymmetricNearDuplicateMapOnlyForRequestedIds() {
		final NewsSimilarityRepository similarityStore = mock(NewsSimilarityRepository.class);
		final FindNearDuplicateNewsIds useCase = new FindNearDuplicateNewsIds(similarityStore);

		when(similarityStore.neighbors("n1", 0.82)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n2", 0.93),
			new SimilarNewsNeighbor("other", 0.99)
		));
		when(similarityStore.neighbors("n2", 0.82)).thenReturn(Flux.just(
			new SimilarNewsNeighbor("n1", 0.93)
		));

		final Map<String, Set<String>> result = useCase.execute(java.util.Arrays.asList("n1", "n2", null, ""), 0.82).block();

		assertEquals(Map.of(
			"n1", Set.of("n2"),
			"n2", Set.of("n1")
		), result);
	}
}
