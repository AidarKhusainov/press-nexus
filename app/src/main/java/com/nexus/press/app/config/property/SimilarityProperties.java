package com.nexus.press.app.config.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "similarity")
public class SimilarityProperties {

	/**
	 * Минимальный порог схожести для отображения «похожих».
	 */
	private double minScore = 0.60;
	/**
	 * Максимальное число «похожих» для одной новости.
	 */
	private int topN = 5;
	/**
	 * Порог для объединения в кластеры (может отличаться от minScore).
	 */
	private double clusterMinScore = 0.70;
}

