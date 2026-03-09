package com.nexus.press.app.repository;

import com.nexus.press.app.repository.entity.NewsEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface NewsRepository extends ReactiveCrudRepository<NewsEntity, String> {

	@Query("SELECT * FROM news WHERE url = :url")
	Mono<NewsEntity> findByUrl(String url);

	@Query("SELECT * FROM news WHERE media = :media AND external_id = :externalId")
	Mono<NewsEntity> findByMediaAndExternalId(String media, String externalId);
}

