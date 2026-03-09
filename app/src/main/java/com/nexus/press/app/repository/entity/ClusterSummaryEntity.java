package com.nexus.press.app.repository.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
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
@Table("cluster_summary")
public class ClusterSummaryEntity {

	// Композитный PK (cluster_id, model, lang); clusterId используется как идентификатор для R2DBC операций.
	@Id
	@Column("cluster_id")
	private UUID clusterId;

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
