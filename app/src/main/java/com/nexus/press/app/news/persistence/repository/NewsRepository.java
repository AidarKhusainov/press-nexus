package com.nexus.press.app.news.persistence.repository;

import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import com.nexus.press.app.news.model.CachedNewsSummary;
import com.nexus.press.app.repository.entity.NewsEntity;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.model.ProcessingStatus;

public interface NewsRepository {

	Mono<NewsEntity> upsertByUrl(NewsUpsertRequest request, String id, OffsetDateTime fetchedAt);

	Mono<NewsEntity> upsertByExternalId(NewsUpsertRequest request, String id, OffsetDateTime fetchedAt);

	Mono<NewsEntity> saveDiscoveredIfAbsent(NewsUpsertRequest request, String id, OffsetDateTime fetchedAt);

	Mono<Void> updateStatusContent(String id, ProcessingStatus status);

	Mono<Void> updateStatusEmbedding(String id, ProcessingStatus status);

	Mono<Void> updateStatusSummary(String id, ProcessingStatus status);

	Mono<Void> saveNewsSummary(String newsId, String model, String lang, String summary, String promptHash);

	Mono<CachedNewsSummary> findReusableSummary(String newsId, String lang);

	Mono<NewsEntity> loadExistingByNaturalKeys(String id, String url);
}
