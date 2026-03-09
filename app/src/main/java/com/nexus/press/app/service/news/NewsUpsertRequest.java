package com.nexus.press.app.service.news;

import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class NewsUpsertRequest {
	String id;
	String media;
	String externalId;
	String url;
	String title;
	String author;
	String language;
	OffsetDateTime publishedAt;
	OffsetDateTime fetchedAt;
	String contentRaw;
	String contentClean;
	ProcessingStatus statusContent;
	ProcessingStatus statusEmbedding;
	ProcessingStatus statusSummary;
}
