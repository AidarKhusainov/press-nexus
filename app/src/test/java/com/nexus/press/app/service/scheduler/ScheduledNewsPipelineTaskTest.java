package com.nexus.press.app.service.scheduler;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.service.news.NewsPipelineWorkerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledNewsPipelineTaskTest {

	private Disposable subscription;

	@AfterEach
	void tearDown() {
		if (subscription != null) {
			subscription.dispose();
		}
	}

	@Test
	void scheduledIngestionLoopWaitsForPreviousDrainCompletionBeforeNextTick() {
		final var workerService = mock(NewsPipelineWorkerService.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsPipelineTask(workerService, properties);
		final var drainResult = new NewsPipelineWorkerService.DrainResult(1, 0, 0);

		when(workerService.drainIngestionOnce())
			.thenReturn(Mono.delay(Duration.ofMillis(50)).thenReturn(drainResult));

		final List<NewsPipelineWorkerService.DrainResult> results = task.scheduledIngestionLoop(Duration.ofMillis(10))
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(1));

		assertEquals(List.of(drainResult, drainResult), results);
		verify(workerService, times(2)).drainIngestionOnce();
	}

	@Test
	void scheduledSummaryLoopRunsIndependentlyFromIngestionLoop() throws Exception {
		final var workerService = mock(NewsPipelineWorkerService.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsPipelineTask(workerService, properties);
		final var ingestionStarted = new CountDownLatch(1);
		final var drainIngestionResult = new NewsPipelineWorkerService.DrainResult(1, 1, 0);
		final var drainSummaryResult = new NewsPipelineWorkerService.DrainResult(0, 0, 8);

		when(workerService.drainIngestionOnce()).thenReturn(
			Mono.defer(() -> {
				ingestionStarted.countDown();
				return Mono.delay(Duration.ofMillis(200)).thenReturn(drainIngestionResult);
			})
		);
		when(workerService.drainSummaryOnce()).thenReturn(Mono.just(drainSummaryResult));

		subscription = task.scheduledIngestionLoop(Duration.ofSeconds(1)).subscribe();
		assertTrue(ingestionStarted.await(1, TimeUnit.SECONDS));

		final var summaryResult = task.scheduledSummaryLoop(Duration.ofSeconds(1))
			.blockFirst(Duration.ofMillis(100));

		assertNotNull(summaryResult);
		assertEquals(drainSummaryResult, summaryResult);
		verify(workerService).drainSummaryOnce();
	}
}
