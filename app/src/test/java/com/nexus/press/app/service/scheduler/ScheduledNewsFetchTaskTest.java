package com.nexus.press.app.service.scheduler;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.NewsFetchService;
import com.nexus.press.app.service.news.NewsPersistenceService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledNewsFetchTaskTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void runFetchCycleDoesNotSkipDiscoveryForSummaryOnlyBacklog() {
		final var newsFetchService = mock(NewsFetchService.class);
		final var persistenceService = mock(NewsPersistenceService.class);
		final var properties = new NewsPipelineProperties();
		properties.setDiscoveryBacklogHighWatermark(500);
		final var task = new ScheduledNewsFetchTask(newsFetchService, persistenceService, properties, APP_METRICS);

		when(persistenceService.loadPipelineBacklog())
			.thenReturn(Mono.just(new NewsPersistenceService.PipelineBacklogSnapshot(0, 0, 0, 0, 0, 0, 900, 40, 0)));
		when(newsFetchService.fetchNews()).thenReturn(Flux.empty());

		task.runFetchCycle("test_fetch_cycle").collectList().block();

		verify(newsFetchService).fetchNews();
	}

	@Test
	void runFetchCycleSkipsDiscoveryForActiveIngestionBacklog() {
		final var newsFetchService = mock(NewsFetchService.class);
		final var persistenceService = mock(NewsPersistenceService.class);
		final var properties = new NewsPipelineProperties();
		properties.setDiscoveryBacklogHighWatermark(500);
		final var task = new ScheduledNewsFetchTask(newsFetchService, persistenceService, properties, APP_METRICS);

		when(persistenceService.loadPipelineBacklog())
			.thenReturn(Mono.just(new NewsPersistenceService.PipelineBacklogSnapshot(320, 210, 0, 30, 5, 0, 900, 40, 0)));

		task.runFetchCycle("test_fetch_cycle").collectList().block();

		verify(newsFetchService, never()).fetchNews();
	}
}
