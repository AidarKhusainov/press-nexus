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
@Table("cluster")
public class ClusterEntity {

	@Id
	@Column("id")
	private UUID id;

	@Column("representative_news_id")
	private String representativeNewsId;

	@Column("created_at")
	private OffsetDateTime createdAt;
}
