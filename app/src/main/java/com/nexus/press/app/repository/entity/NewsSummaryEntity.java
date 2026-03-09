package com.nexus.press.app.repository.entity;

import java.time.OffsetDateTime;
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
@Table("news_summary")
public class NewsSummaryEntity {

	// Композитный PK (news_id, model, lang); newsId используется как идентификатор для R2DBC операций.
	@Id
	@Column("news_id")
	private String newsId;

	@Column("model")
	private String model;

	@Column("lang")
	private String lang;

	@Column("summary")
	private String summary;

	@Column("prompt_hash")
	private String promptHash;

	@Column("created_at")
	private OffsetDateTime createdAt;
}
