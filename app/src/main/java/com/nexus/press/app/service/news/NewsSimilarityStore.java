package com.nexus.press.app.service.news;

import java.util.List;

/**
 * Хранилище значений схожести новостей. Реализация по умолчанию — in-memory.
 */
public interface NewsSimilarityStore {

	/**
	 * Сохранить схожесть между двумя новостями.
	 */
	void put(String idA, String idB, double similarity);

	/**
	 * Получить топ-N похожих для новости, отсортировано по убыванию сходства.
	 */
	List<SimilarItem> topSimilar(String id, int topN, double minScore);

	/**
	 * Зарегистрировать/обновить эмбеддинг новости.
	 */
	void upsertEmbedding(String id, float[] embedding);

	/**
	 * Получить список всех эмбеддингов (id, embedding).
	 */
	List<EmbeddingItem> allEmbeddings();

	/**
	 * Все идентификаторы, для которых известны эмбеддинги.
	 */
	List<String> allIds();

	/**
	 * Соседи новости по схожести не ниже порога.
	 */
	List<SimilarItem> neighbors(String id, double minScore);

	record SimilarItem(String id, double score) {}

	record EmbeddingItem(String id, float[] embedding) {}
}
