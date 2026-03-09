package com.nexus.press.app.service.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryNewsSimilarityStore implements NewsSimilarityStore {

	private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();
	// симметричное хранение: id -> (otherId -> sim)
	private final Map<String, Map<String, Double>> sims = new ConcurrentHashMap<>();

	@Override
	public void put(final String idA, final String idB, final double similarity) {
		if (idA.equals(idB)) return;
		sims.computeIfAbsent(idA, k -> new ConcurrentHashMap<>()).put(idB, similarity);
		sims.computeIfAbsent(idB, k -> new ConcurrentHashMap<>()).put(idA, similarity);
	}

	@Override
	public List<SimilarItem> topSimilar(final String id, final int topN, final double minScore) {
		final var map = sims.get(id);
		if (map == null || map.isEmpty()) return Collections.emptyList();
		final List<SimilarItem> list = new ArrayList<>(map.size());
		for (final var e : map.entrySet()) {
			if (e.getValue() >= minScore) list.add(new SimilarItem(e.getKey(), e.getValue()));
		}
		list.sort(Comparator.comparingDouble(SimilarItem::score).reversed());
		return list.size() > topN ? list.subList(0, topN) : list;
	}

	@Override
	public void upsertEmbedding(final String id, final float[] embedding) {
		embeddings.put(id, embedding);
	}

	@Override
	public List<EmbeddingItem> allEmbeddings() {
		final List<EmbeddingItem> list = new ArrayList<>(embeddings.size());
		embeddings.forEach((k, v) -> list.add(new EmbeddingItem(k, v)));
		return list;
	}

	@Override
	public List<String> allIds() {
		return new ArrayList<>(embeddings.keySet());
	}

	@Override
	public List<SimilarItem> neighbors(final String id, final double minScore) {
		final var map = sims.get(id);
		if (map == null || map.isEmpty()) return Collections.emptyList();
		final List<SimilarItem> list = new ArrayList<>(map.size());
		for (final var e : map.entrySet()) {
			if (e.getValue() >= minScore) list.add(new SimilarItem(e.getKey(), e.getValue()));
		}
		list.sort(Comparator.comparingDouble(SimilarItem::score).reversed());
		return list;
	}
}
