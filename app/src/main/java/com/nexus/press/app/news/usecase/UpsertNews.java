package com.nexus.press.app.news.usecase;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.time.OffsetDateTime;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.repository.entity.NewsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpsertNews {

	private static final Duration DUPLICATE_LOOKUP_BACKOFF = Duration.ofMillis(100);
	private static final int DUPLICATE_LOOKUP_RETRIES = 4;

	private final NewsRepository newsRepository;

	public Mono<String> execute(final NewsUpsertRequest request) {
		final String id = request.getId() != null ? request.getId() : java.util.UUID.randomUUID().toString();
		final OffsetDateTime fetchedAt = request.getFetchedAt() != null ? request.getFetchedAt() : OffsetDateTime.now();

		return newsRepository.upsertByUrl(request, id, fetchedAt)
			.map(NewsEntity::getId)
			.onErrorResume(DuplicateKeyException.class, ex -> resolveDuplicateUpsert(request, id, fetchedAt).map(NewsEntity::getId));
	}

	private Mono<NewsEntity> resolveDuplicateUpsert(
		final NewsUpsertRequest request,
		final String id,
		final OffsetDateTime fetchedAt
	) {
		if (StringUtils.hasText(request.getExternalId())) {
			log.warn("URL upsert conflict, retry by (media, external_id): url={} media={} extId={}",
				request.getUrl(), request.getMedia(), request.getExternalId());
			return newsRepository.upsertByExternalId(request, id, fetchedAt)
				.onErrorResume(DuplicateKeyException.class, ex -> resolveExistingByNaturalKeysForDuplicate(id, request.getUrl())
					.onErrorResume(IllegalStateException.class, missing -> {
						log.warn("Не удалось найти существующую запись после duplicate conflict по external_id, повторяем upsert: id={} url={} extId={}",
							id, request.getUrl(), request.getExternalId());
						return retryUpsertAfterMissingExisting(
							newsRepository.upsertByExternalId(request, id, fetchedAt),
							missing
						);
					}));
		}

		log.warn("URL upsert conflict without external_id, resolving existing row by id/url: id={} url={}", id, request.getUrl());
		return resolveExistingByNaturalKeysForDuplicate(id, request.getUrl())
			.onErrorResume(IllegalStateException.class, ex -> {
				log.warn("Не удалось найти существующую запись после duplicate conflict, повторяем URL upsert: id={} url={}",
					id, request.getUrl());
				return retryUpsertAfterMissingExisting(newsRepository.upsertByUrl(request, id, fetchedAt), ex);
			});
	}

	private Mono<NewsEntity> retryUpsertAfterMissingExisting(
		final Mono<NewsEntity> retryUpsert,
		final IllegalStateException missingExisting
	) {
		return retryUpsert.onErrorMap(DuplicateKeyException.class, duplicate -> missingExisting);
	}

	Mono<NewsEntity> resolveExistingByNaturalKeysForDuplicate(final String id, final String url) {
		return Mono.defer(() -> loadExistingByNaturalKeys(id, url)
				.switchIfEmpty(Mono.error(new ExistingDuplicateRowNotVisibleYetException())))
			.retryWhen(
				Retry.backoff(DUPLICATE_LOOKUP_RETRIES, DUPLICATE_LOOKUP_BACKOFF)
					.filter(ExistingDuplicateRowNotVisibleYetException.class::isInstance)
					.doBeforeRetry(retrySignal -> log.warn(
						"Повторяем поиск существующей строки после duplicate conflict: attempt={} id={} url={}",
						retrySignal.totalRetries() + 1,
						id,
						url
					))
					.onRetryExhaustedThrow((spec, retrySignal) -> retrySignal.failure())
			)
			.onErrorMap(
				ExistingDuplicateRowNotVisibleYetException.class,
				ex -> new IllegalStateException("Не удалось разрешить конфликт upsert: существующая запись не найдена", ex)
			);
	}

	Mono<NewsEntity> loadExistingByNaturalKeys(final String id, final String url) {
		return newsRepository.loadExistingByNaturalKeys(id, url);
	}

	private static final class ExistingDuplicateRowNotVisibleYetException extends IllegalStateException {
		private ExistingDuplicateRowNotVisibleYetException() {
			super("Existing duplicate row not visible yet");
		}
	}
}
