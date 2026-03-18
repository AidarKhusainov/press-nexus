package com.nexus.press.app.service.news;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.nexus.press.app.repository.entity.NewsEntity;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class NewsPersistenceServiceTest {

	@Test
	void resolveExistingByNaturalKeysRetriesUntilRowBecomesVisible() {
		final var attempts = new AtomicInteger();
		final var expected = NewsEntity.builder()
			.id("news-1")
			.url("https://example.com/news-1")
			.contentHash("hash-1")
			.build();
		final var service = new NewsPersistenceService(mock(DatabaseClient.class)) {
			@Override
			Mono<NewsEntity> loadExistingByNaturalKeys(final String id, final String url, final String contentHash) {
				return attempts.incrementAndGet() < 3
					? Mono.empty()
					: Mono.just(expected);
			}
		};

		final var resolved = service.resolveExistingByNaturalKeysForDuplicate("news-1", "https://example.com/news-1", "hash-1")
			.block(Duration.ofSeconds(5));

		assertNotNull(resolved);
		assertEquals("news-1", resolved.getId());
		assertEquals(3, attempts.get());
	}

	@Test
	void resolveExistingByNaturalKeysFailsAfterRetryBudgetExhausted() {
		final var attempts = new AtomicInteger();
		final var service = new NewsPersistenceService(mock(DatabaseClient.class)) {
			@Override
			Mono<NewsEntity> loadExistingByNaturalKeys(final String id, final String url, final String contentHash) {
				attempts.incrementAndGet();
				return Mono.empty();
			}
		};

		final var ex = assertThrows(IllegalStateException.class, () ->
			service.resolveExistingByNaturalKeysForDuplicate("news-1", "https://example.com/news-1", "hash-1")
				.block(Duration.ofSeconds(5))
		);

		assertEquals("Не удалось разрешить конфликт upsert: существующая запись не найдена", ex.getMessage());
		assertEquals(5, attempts.get());
	}
}
