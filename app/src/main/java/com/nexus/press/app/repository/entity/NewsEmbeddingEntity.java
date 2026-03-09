package com.nexus.press.app.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("news_embedding")
public class NewsEmbeddingEntity {

	@Id
	@Column("news_id")
	private String newsId;

	// pgvector хранится как vector, в коде используем float[] для чтения/записи через каст и DatabaseClient.
	@Column("embedding")
	private float[] embedding;

	@Column("model")
	private String model;

	@Column("metric")
	private String metric;
}
