package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.nexus.press.app.news.model.SimilarNewsNeighbor;
import com.nexus.press.app.news.persistence.repository.NewsSimilarityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FindNearDuplicateNewsIds {

	private final NewsSimilarityRepository newsSimilarityRepository;

	public Mono<Map<String, Set<String>>> execute(final Collection<String> newsIds, final double minScore) {
		if (newsIds == null || newsIds.isEmpty()) {
			return Mono.just(Map.of());
		}

		final Set<String> candidateIds = newsIds.stream()
			.filter(id -> id != null && !id.isBlank())
			.collect(Collectors.toSet());
		if (candidateIds.isEmpty()) {
			return Mono.just(Map.of());
		}

		return Flux.fromIterable(candidateIds)
			.flatMap(id -> newsSimilarityRepository.neighbors(id, minScore)
				.map(SimilarNewsNeighbor::id)
				.filter(candidateIds::contains)
				.map(neighborId -> Map.entry(id, neighborId)))
			.collectList()
			.map(pairs -> {
				final Map<String, Set<String>> nearDuplicateIds = new HashMap<>();
				for (final Map.Entry<String, String> pair : pairs) {
					nearDuplicateIds.computeIfAbsent(pair.getKey(), ignored -> new HashSet<>()).add(pair.getValue());
					nearDuplicateIds.computeIfAbsent(pair.getValue(), ignored -> new HashSet<>()).add(pair.getKey());
				}
				return nearDuplicateIds;
			});
	}
}
