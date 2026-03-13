package com.nexus.press.app.service.scheduler;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.service.news.NewsPipelineWorkerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledNewsPipelineTaskTest {

	@Test
	void scheduledDrainLoopWaitsForPreviousDrainCompletionBeforeNextTick() {
		final var workerService = mock(NewsPipelineWorkerService.class);
		final var properties = new NewsPipelineProperties();
		final var task = new ScheduledNewsPipelineTask(workerService, properties);
		final var drainResult = new NewsPipelineWorkerService.DrainResult(1, 0, 0);

		when(workerService.drainOnce())
			.thenReturn(Mono.delay(Duration.ofMillis(50)).thenReturn(drainResult));

		final List<NewsPipelineWorkerService.DrainResult> results = task.scheduledDrainLoop(Duration.ofMillis(10))
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(1));

		assertEquals(List.of(drainResult, drainResult), results);
		verify(workerService, times(2)).drainOnce();
	}
}
