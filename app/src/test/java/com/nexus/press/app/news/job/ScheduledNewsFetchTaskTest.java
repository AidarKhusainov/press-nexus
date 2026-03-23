package com.nexus.press.app.news.job;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.usecase.FetchNews;
import com.nexus.press.app.observability.AppMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledNewsFetchTaskTest {

	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());

	@Test
	void runFetchCycleFetchesNews() {
		final var fetchNews = mock(FetchNews.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsFetchTask(fetchNews, properties, APP_METRICS);
		when(fetchNews.execute()).thenReturn(Flux.empty());

		task.runFetchCycle("test_fetch_cycle").collectList().block();

		verify(fetchNews).execute();
	}

	@Test
	void scheduleFetchCyclesWaitsForPreviousCycleToFinishBeforeStartingNext() {
		final var fetchNews = mock(FetchNews.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsFetchTask(fetchNews, properties, APP_METRICS);
		final var invocations = new AtomicInteger();
		final var secondInvocationLatch = new CountDownLatch(1);
		when(fetchNews.execute()).thenAnswer(invocation -> {
			final int currentInvocation = invocations.incrementAndGet();
			if (currentInvocation == 2) {
				secondInvocationLatch.countDown();
			}
			return Mono.delay(Duration.ofMillis(200)).thenMany(Flux.empty());
		});

		final var subscription = task.scheduleFetchCycles(Duration.ofMillis(50)).subscribe();
		try {
			Thread.sleep(120);
			assertEquals(1, invocations.get());
			assertTrue(secondInvocationLatch.await(500, TimeUnit.MILLISECONDS));
			assertEquals(2, invocations.get());
		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		} finally {
			subscription.dispose();
		}
	}
}
