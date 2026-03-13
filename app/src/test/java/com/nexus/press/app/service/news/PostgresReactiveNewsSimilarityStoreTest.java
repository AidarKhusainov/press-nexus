package com.nexus.press.app.service.news;

import reactor.core.publisher.Flux;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresReactiveNewsSimilarityStoreTest {

	@Test
	void topSimilarUsesCosineDistanceOperator() {
		final var db = mock(DatabaseClient.class);
		final var spec = mock(DatabaseClient.GenericExecuteSpec.class);
		@SuppressWarnings("unchecked")
		final RowsFetchSpec<NewsSimilarityStore.SimilarItem> rows = mock(RowsFetchSpec.class);
		final var service = new PostgresReactiveNewsSimilarityStore(db);

		when(db.sql(anyString())).thenReturn(spec);
		when(spec.bind(anyString(), any())).thenReturn(spec);
		doReturn(rows).when(spec).map(anyBiFunction());
		when(rows.all()).thenReturn(Flux.empty());

		service.topSimilar("news-1", 5, 0.93).collectList().block();

		final var sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(db).sql(sqlCaptor.capture());
		assertUsesCosineDistance(sqlCaptor.getValue());
	}

	@Test
	void neighborsUsesCosineDistanceOperator() {
		final var db = mock(DatabaseClient.class);
		final var spec = mock(DatabaseClient.GenericExecuteSpec.class);
		@SuppressWarnings("unchecked")
		final RowsFetchSpec<NewsSimilarityStore.SimilarItem> rows = mock(RowsFetchSpec.class);
		final var service = new PostgresReactiveNewsSimilarityStore(db);

		when(db.sql(anyString())).thenReturn(spec);
		when(spec.bind(anyString(), any())).thenReturn(spec);
		doReturn(rows).when(spec).map(anyBiFunction());
		when(rows.all()).thenReturn(Flux.empty());

		service.neighbors("news-1", 0.93).collectList().block();

		final var sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(db).sql(sqlCaptor.capture());
		assertUsesCosineDistance(sqlCaptor.getValue());
	}

	private static void assertUsesCosineDistance(final String sql) {
		assertTrue(sql.contains("1 - (n.embedding <=> q.embedding) AS score"));
		assertTrue(sql.contains("1 - (n.embedding <=> q.embedding) >= :min"));
		assertTrue(sql.contains("ORDER BY n.embedding <=> q.embedding"));
		assertFalse(sql.contains("<#>"));
	}

	@SuppressWarnings("unchecked")
	private static BiFunction<Row, RowMetadata, NewsSimilarityStore.SimilarItem> anyBiFunction() {
		return (BiFunction<Row, RowMetadata, NewsSimilarityStore.SimilarItem>) any(BiFunction.class);
	}
}
