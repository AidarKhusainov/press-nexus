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
@Table("category")
public class CategoryEntity {

	@Id
	@Column("id")
	private UUID id;

	@Column("slug")
	private String slug;

	@Column("name")
	private String name;

	@Column("parent_id")
	private UUID parentId;

	@Column("description")
	private String description;

	@Column("created_at")
	private OffsetDateTime createdAt;
}
