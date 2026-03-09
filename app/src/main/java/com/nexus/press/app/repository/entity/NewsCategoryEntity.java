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
@Table("news_category")
public class NewsCategoryEntity {

	// Композитный PK (news_id, category_id); здесь используем newsId как идентификатор для R2DBC.
	@Id
	@Column("news_id")
	private String newsId;

	@Column("category_id")
	private java.util.UUID categoryId;
}
