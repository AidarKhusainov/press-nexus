package com.nexus.press.app.service.scheduler;

import reactor.core.publisher.Flux;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.NewsFetchService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledNewsFetchTaskTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void runFetchCycleFetchesNews() {
		final var newsFetchService = mock(NewsFetchService.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsFetchTask(newsFetchService, properties, APP_METRICS);
		when(newsFetchService.fetchNews()).thenReturn(Flux.empty());

		task.runFetchCycle("test_fetch_cycle").collectList().block();

		verify(newsFetchService).fetchNews();
	}
}
