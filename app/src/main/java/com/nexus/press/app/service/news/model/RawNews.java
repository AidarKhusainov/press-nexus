package com.nexus.press.app.service.news.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.With;
import lombok.experimental.SuperBuilder;

@Getter
@ToString
@SuperBuilder
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(force = true)
public class RawNews {

	private final String id;
	private final String link;
	private final String title;
	private final String description;
	@With
	private final String rawContent;
	@With
	private final String cleanContent;
	private final Media source;
	private final OffsetDateTime publishedDate;
	private final String language;
}
