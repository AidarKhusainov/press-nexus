package com.nexus.press.app.news.usecase;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import com.nexus.press.app.news.model.NewsUpsertRequest;
import com.nexus.press.app.news.persistence.repository.NewsRepository;
import com.nexus.press.app.news.usecase.UpsertNews;
import com.nexus.press.app.repository.entity.NewsEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpsertNewsTest {

	@Test
	void resolveExistingByNaturalKeysRetriesUntilRowBecomesVisible() {
		final var attempts = new AtomicInteger();
		final var expected = NewsEntity.builder()
			.id("news-1")
			.url("https://example.com/news-1")
			.build();
		final var newsRepository = mock(NewsRepository.class);
		when(newsRepository.upsertByUrl(any(), eq("news-1"), any()))
			.thenReturn(Mono.error(new DuplicateKeyException("duplicate")));
		when(newsRepository.loadExistingByNaturalKeys("news-1", "https://example.com/news-1"))
			.thenAnswer(invocation -> {
				final int attempt = attempts.incrementAndGet();
				return attempt < 3 ? Mono.empty() : Mono.just(expected);
			});

		final var service = new UpsertNews(newsRepository);

		final var resolvedId = service.execute(request("news-1", "https://example.com/news-1"))
			.block(Duration.ofSeconds(5));

		assertEquals("news-1", resolvedId);
		assertEquals(3, attempts.get());
	}

	@Test
	void resolveExistingByNaturalKeysFailsAfterRetryBudgetExhausted() {
		final var attempts = new AtomicInteger();
		final var newsRepository = mock(NewsRepository.class);
		when(newsRepository.upsertByUrl(any(), eq("news-1"), any()))
			.thenReturn(Mono.error(new DuplicateKeyException("duplicate")));
		when(newsRepository.loadExistingByNaturalKeys("news-1", "https://example.com/news-1"))
			.thenAnswer(invocation -> {
				attempts.incrementAndGet();
				return Mono.empty();
			});

		final var service = new UpsertNews(newsRepository);

		final var ex = assertThrows(IllegalStateException.class, () ->
			service.execute(request("news-1", "https://example.com/news-1"))
				.block(Duration.ofSeconds(5))
		);

		assertEquals("Не удалось разрешить конфликт upsert: существующая запись не найдена", ex.getMessage());
		assertEquals(5, attempts.get());
	}

	private static NewsUpsertRequest request(final String id, final String url) {
		return NewsUpsertRequest.builder()
			.id(id)
			.media("BBC")
			.url(url)
			.title("Title")
			.fetchedAt(OffsetDateTime.parse("2026-03-20T00:00:00Z"))
			.build();
	}
}
