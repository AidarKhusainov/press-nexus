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
@Table("news")
public class NewsEntity {

	@Id
	@Column("id")
	private String id;

	@Column("media")
	private String media;

	@Column("external_id")
	private String externalId;

	@Column("url")
	private String url;

	@Column("title")
	private String title;

	@Column("author")
	private String author;

	@Column("language")
	private String language;

	@Column("published_at")
	private OffsetDateTime publishedAt;

	@Column("fetched_at")
	private OffsetDateTime fetchedAt;

	@Column("content_raw")
	private String contentRaw;

	@Column("content_clean")
	private String contentClean;

	@Column("content_hash")
	private String contentHash;

	@Column("status_content")
	private String statusContent;

	@Column("status_embedding")
	private String statusEmbedding;

	@Column("status_summary")
	private String statusSummary;

	@Column("created_at")
	private OffsetDateTime createdAt;

	@Column("updated_at")
	private OffsetDateTime updatedAt;
}
