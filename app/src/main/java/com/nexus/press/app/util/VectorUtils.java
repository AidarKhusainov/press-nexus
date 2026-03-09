package com.nexus.press.app.util;

import java.util.List;

/**
 * Утилитный класс для работы с векторами эмбеддингов.
 * <p>
 * Содержит методы для трёх базовых операций:
 * <ul>
 *     <li><b>L2-нормализация</b> — приводит длину вектора к единице,
 *     чтобы корректно сравнивать эмбеддинги между собой.</li>
 *     <li><b>Среднее (mean) пуллинг</b> — усредняет набор векторов в один
 *     общий вектор, например, при объединении эмбеддингов нескольких чанков текста.</li>
 *     <li><b>Косинусное сходство</b> — вычисляет, насколько два вектора похожи по направлению,
 *     то есть измеряет их смысловую близость.</li>
 * </ul>
 *
 * <p>Интерпретация значения косинусного сходства для текстовых эмбеддингов:
 * <ul>
 *     <li> > 0.80 — тексты почти идентичны (дубликаты)</li>
 *     <li> 0.60–0.80 — тексты схожи по смыслу, но с отличиями</li>
 *     <li> < 0.60 — тексты разные</li>
 * </ul>
 *
 * <p>Пример использования:
 * <pre>{@code
 * float[] norm = VectorUtils.l2Normalize(vector);
 * float[] pooled = VectorUtils.meanPool(listOfVectors);
 * double sim = VectorUtils.cosine(v1, v2);
 * }</pre>
 */
public final class VectorUtils {

	private VectorUtils() {
	}

	/**
	 * Выполняет L2-нормализацию вектора — масштабирует его так, чтобы длина (норма) стала равна 1.
	 * <p>
	 * Это необходимо для того, чтобы при сравнении косинусного сходства
	 * направление вектора имело значение, а не его абсолютная длина.
	 *
	 * @param v исходный вектор
	 * @return новый нормализованный вектор
	 */
	public static float[] l2Normalize(final float[] v) {
		double norm = 0;
		for (final float x : v) norm += x * x;
		norm = Math.sqrt(norm);
		if (norm == 0) return v.clone();
		final float[] out = new float[v.length];
		for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
		return out;
	}

	/**
	 * Вычисляет усреднённый (mean) вектор из списка векторов одинаковой размерности.
	 * <p>
	 * Применяется, например, для объединения эмбеддингов отдельных чанков текста
	 * в один общий эмбеддинг всего документа.
	 *
	 * @param vectors список векторов одинаковой длины
	 * @return усреднённый вектор
	 * @throws IllegalArgumentException если векторы имеют разную размерность
	 */
	public static float[] meanPool(final List<float[]> vectors) {
		if (vectors == null || vectors.isEmpty()) return new float[0];
		final int d = vectors.getFirst().length;
		final float[] sum = new float[d];
		for (final float[] v : vectors) {
			if (v.length != d) throw new IllegalArgumentException("Векторы имеют разную размерность");
			for (int i = 0; i < d; i++) sum[i] += v[i];
		}
		for (int i = 0; i < d; i++) sum[i] /= vectors.size();
		return sum;
	}

	/**
	 * Вычисляет косинусное сходство между двумя векторами.
	 * <p>
	 * Результат лежит в диапазоне [-1, 1]:
	 * <ul>
	 *     <li><b>1.0</b> — векторы направлены одинаково (максимальное сходство)</li>
	 *     <li><b>0.0</b> — векторы ортогональны (нет связи)</li>
	 *     <li><b>-1.0</b> — векторы противоположны по направлению</li>
	 * </ul>
	 *
	 * @param a первый вектор
	 * @param b второй вектор
	 * @return значение косинусного сходства
	 */
	public static double cosine(final float[] a, final float[] b) {
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}
}
