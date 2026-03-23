package com.nexus.press.app.news.job;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.news.model.NewsPipelineDrainResult;
import com.nexus.press.app.news.usecase.DrainNewsPipelineIngestion;
import com.nexus.press.app.news.usecase.DrainNewsPipelineSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
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
		final var drainNewsPipelineIngestion = mock(DrainNewsPipelineIngestion.class);
		final var drainNewsPipelineSummary = mock(DrainNewsPipelineSummary.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsPipelineTask(
			drainNewsPipelineIngestion,
			drainNewsPipelineSummary,
			properties
		);
		final var drainResult = new NewsPipelineDrainResult(1, 0, 0);

		when(drainNewsPipelineIngestion.execute())
			.thenReturn(Mono.delay(Duration.ofMillis(50)).thenReturn(drainResult));

		final List<NewsPipelineDrainResult> results = task.scheduledIngestionLoop(Duration.ofMillis(10))
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(1));

		assertEquals(List.of(drainResult, drainResult), results);
		verify(drainNewsPipelineIngestion, times(2)).execute();
	}

	@Test
	void scheduledSummaryLoopRunsIndependentlyFromIngestionLoop() throws Exception {
		final var drainNewsPipelineIngestion = mock(DrainNewsPipelineIngestion.class);
		final var drainNewsPipelineSummary = mock(DrainNewsPipelineSummary.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsPipelineTask(
			drainNewsPipelineIngestion,
			drainNewsPipelineSummary,
			properties
		);
		final var ingestionStarted = new CountDownLatch(1);
		final var drainIngestionResult = new NewsPipelineDrainResult(1, 1, 0);
		final var drainSummaryResult = new NewsPipelineDrainResult(0, 0, 8);

		when(drainNewsPipelineIngestion.execute()).thenReturn(
			Mono.defer(() -> {
				ingestionStarted.countDown();
				return Mono.delay(Duration.ofMillis(200)).thenReturn(drainIngestionResult);
			})
		);
		when(drainNewsPipelineSummary.execute()).thenReturn(Mono.just(drainSummaryResult));

		subscription = task.scheduledIngestionLoop(Duration.ofSeconds(1)).subscribe();
		assertTrue(ingestionStarted.await(1, TimeUnit.SECONDS));

		final var summaryResult = task.scheduledSummaryLoop(Duration.ofSeconds(1))
			.blockFirst(Duration.ofMillis(100));

		assertNotNull(summaryResult);
		assertEquals(drainSummaryResult, summaryResult);
		verify(drainNewsPipelineSummary).execute();
	}

	@Test
	void startDoesNotScheduleSummaryLoopWhenSummaryDisabled() {
		final var drainNewsPipelineIngestion = mock(DrainNewsPipelineIngestion.class);
		final var drainNewsPipelineSummary = mock(DrainNewsPipelineSummary.class);
		final var properties = new NewsPipelineProperties();
		properties.setSummaryEnabled(false);
		final var task = new ScheduledNewsPipelineTask(
			drainNewsPipelineIngestion,
			drainNewsPipelineSummary,
			properties
		);

		when(drainNewsPipelineIngestion.execute()).thenReturn(Mono.just(new NewsPipelineDrainResult(0, 0, 0)));

		task.start();

		verify(drainNewsPipelineSummary, never()).execute();
		task.stop();
	}
}
