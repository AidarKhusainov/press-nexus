package com.nexus.press.app.repository.entity;

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
@Table("cluster_membership")
public class ClusterMembershipEntity {

	// Композитный PK (cluster_id, news_id); используем clusterId как ключ для R2DBC операций.
	@Id
	@Column("cluster_id")
	private UUID clusterId;

	@Column("news_id")
	private String newsId;
}
