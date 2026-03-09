package com.nexus.press.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class AppMetrics {

	private final MeterRegistry meterRegistry;
	private final ConcurrentMap<String, AtomicInteger> queueDepthByName = new ConcurrentHashMap<>();

	public AppMetrics(final MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void registerQueue(final String queueName) {
		queueDepthByName.computeIfAbsent(queueName, name -> {
			final var queueDepth = new AtomicInteger(0);
			Gauge.builder("press.queue.depth", queueDepth, AtomicInteger::get)
				.description("Current queue depth")
				.tag("queue", name)
				.register(meterRegistry);
			return queueDepth;
		});
	}

	public void queueEnqueued(final String queueName) {
		registerQueue(queueName);
		queueDepthByName.get(queueName).incrementAndGet();
		Counter.builder("press.queue.events")
			.description("Queue events")
			.tags("queue", queueName, "event", "enqueued")
			.register(meterRegistry)
			.increment();
	}

	public void queueDequeued(final String queueName) {
		registerQueue(queueName);
		queueDepthByName.get(queueName).updateAndGet(value -> Math.max(0, value - 1));
		Counter.builder("press.queue.events")
			.description("Queue events")
			.tags("queue", queueName, "event", "dequeued")
			.register(meterRegistry)
			.increment();
	}

	public void queueEmitFailed(final String queueName) {
		Counter.builder("press.queue.events")
			.description("Queue events")
			.tags("queue", queueName, "event", "emit_failed")
			.register(meterRegistry)
			.increment();
	}

	public Timer.Sample startStageTimer() {
		return Timer.start(meterRegistry);
	}

	public void stageSuccess(final String stage, final Timer.Sample sample) {
		recordStageEvent(stage, "success");
		sample.stop(Timer.builder("press.pipeline.stage.duration")
			.description("Pipeline stage duration")
			.tags("stage", stage, "outcome", "success")
			.register(meterRegistry));
	}

	public void stageFailure(final String stage, final Timer.Sample sample, final Throwable throwable) {
		recordStageEvent(stage, "failure");
		sample.stop(Timer.builder("press.pipeline.stage.duration")
			.description("Pipeline stage duration")
			.tags("stage", stage, "outcome", "failure")
			.register(meterRegistry));
		Counter.builder("press.pipeline.stage.errors")
			.description("Pipeline stage errors")
			.tags("stage", stage, "error", errorTag(throwable))
			.register(meterRegistry)
			.increment();
	}

	private void recordStageEvent(final String stage, final String outcome) {
		Counter.builder("press.pipeline.stage.events")
			.description("Pipeline stage events")
			.tags("stage", stage, "outcome", outcome)
			.register(meterRegistry)
			.increment();
	}

	public Timer.Sample startHttpClientTimer() {
		return Timer.start(meterRegistry);
	}

	public void httpClientResponse(
		final String client,
		final String method,
		final int status,
		final Timer.Sample sample
	) {
		final String outcome = status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success";
		recordHttpClientCounter(client, method, outcome, Integer.toString(status));
		sample.stop(Timer.builder("press.http.client.duration")
			.description("External HTTP call duration")
			.tags("client", client, "method", method, "outcome", outcome, "status", Integer.toString(status))
			.register(meterRegistry));
	}

	public void httpClientFailure(
		final String client,
		final String method,
		final Throwable throwable,
		final Timer.Sample sample
	) {
		recordHttpClientCounter(client, method, "failure", "IO_ERROR");
		sample.stop(Timer.builder("press.http.client.duration")
			.description("External HTTP call duration")
			.tags("client", client, "method", method, "outcome", "failure", "status", "IO_ERROR")
			.register(meterRegistry));
		Counter.builder("press.http.client.errors")
			.description("External HTTP call errors")
			.tags("client", client, "method", method, "error", errorTag(throwable))
			.register(meterRegistry)
			.increment();
	}

	public void httpClientRetry(final String client) {
		Counter.builder("press.http.client.retries")
			.description("External HTTP retries")
			.tags("client", client)
			.register(meterRegistry)
			.increment();
	}

	private void recordHttpClientCounter(
		final String client,
		final String method,
		final String outcome,
		final String status
	) {
		Counter.builder("press.http.client.requests")
			.description("External HTTP calls")
			.tags("client", client, "method", method, "outcome", outcome, "status", status)
			.register(meterRegistry)
			.increment();
	}

	public Timer.Sample startJobTimer() {
		return Timer.start(meterRegistry);
	}

	public void jobSuccess(final String job, final Timer.Sample sample) {
		recordJobEvent(job, "success");
		sample.stop(Timer.builder("press.jobs.duration")
			.description("Background job duration")
			.tags("job", job, "outcome", "success")
			.register(meterRegistry));
	}

	public void jobFailure(final String job, final Timer.Sample sample, final Throwable throwable) {
		recordJobEvent(job, "failure");
		sample.stop(Timer.builder("press.jobs.duration")
			.description("Background job duration")
			.tags("job", job, "outcome", "failure")
			.register(meterRegistry));
		Counter.builder("press.jobs.errors")
			.description("Background job errors")
			.tags("job", job, "error", errorTag(throwable))
			.register(meterRegistry)
			.increment();
	}

	private void recordJobEvent(final String job, final String outcome) {
		Counter.builder("press.jobs.runs")
			.description("Background job runs")
			.tags("job", job, "outcome", outcome)
			.register(meterRegistry)
			.increment();
	}

	public void deliveryMessageSuccess(final String channel) {
		deliveryMessageCounter(channel, "success").increment();
	}

	public void deliveryMessageFailure(final String channel) {
		deliveryMessageCounter(channel, "failure").increment();
	}

	public void deliveryBatchSent(final String channel, final int sentCount) {
		DistributionSummary.builder("press.delivery.batch.sent")
			.description("Number of messages sent in one delivery batch")
			.baseUnit("messages")
			.tags(List.of(Tag.of("channel", channel)))
			.register(meterRegistry)
			.record(Math.max(0, sentCount));
	}

	private Counter deliveryMessageCounter(final String channel, final String outcome) {
		return Counter.builder("press.delivery.messages")
			.description("Delivery message attempts")
			.tags("channel", channel, "outcome", outcome)
			.register(meterRegistry);
	}

	public void onboardingCompleted(final String channel, final Duration duration) {
		Counter.builder("press.onboarding.completed")
			.description("Number of completed onboardings")
			.tags("channel", channel)
			.register(meterRegistry)
			.increment();

		final double completionSeconds = duration == null ? 0.0 : Math.max(0.0, duration.toMillis() / 1000.0);
		DistributionSummary.builder("press.onboarding.completion.seconds")
			.description("Onboarding completion time in seconds")
			.baseUnit("seconds")
			.tags("channel", channel)
			.register(meterRegistry)
			.record(completionSeconds);
	}

	private String errorTag(final Throwable throwable) {
		if (throwable == null) {
			return "UNKNOWN";
		}
		final var simpleName = throwable.getClass().getSimpleName();
		return simpleName == null || simpleName.isBlank() ? throwable.getClass().getName() : simpleName;
	}
}
