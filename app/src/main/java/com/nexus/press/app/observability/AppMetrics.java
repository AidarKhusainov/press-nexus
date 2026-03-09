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
	private final ProductReportSnapshotGaugeValues productReportSnapshotGaugeValues = new ProductReportSnapshotGaugeValues();

	public AppMetrics(final MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		registerProductReportSnapshotGauges();
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

	public void productReportSnapshot(
		final double d1RetentionPct,
		final double d7RetentionPct,
		final double usefulRatePct,
		final double noiseRatePct,
		final double feedbackCtrPct,
		final double premiumIntentPct,
		final int deliveryUsers,
		final int feedbackUsers,
		final int usefulCount,
		final int noiseCount,
		final int anxiousCount,
		final int d1CohortSize,
		final int d7CohortSize
	) {
		productReportSnapshotGaugeValues.update(
			d1RetentionPct,
			d7RetentionPct,
			usefulRatePct,
			noiseRatePct,
			feedbackCtrPct,
			premiumIntentPct,
			deliveryUsers,
			feedbackUsers,
			usefulCount,
			noiseCount,
			anxiousCount,
			d1CohortSize,
			d7CohortSize
		);
	}

	private void registerProductReportSnapshotGauges() {
		Gauge.builder("press.product.report.d1.retention.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::d1RetentionPct)
			.description("Latest D1 retention percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.d7.retention.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::d7RetentionPct)
			.description("Latest D7 retention percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.useful.rate.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::usefulRatePct)
			.description("Latest useful feedback rate percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.noise.rate.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::noiseRatePct)
			.description("Latest noise feedback rate percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.feedback.ctr.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::feedbackCtrPct)
			.description("Latest feedback CTR percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.premium.intent.pct", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::premiumIntentPct)
			.description("Latest premium intent percent from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.delivery.users", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::deliveryUsers)
			.description("Latest delivered users count from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.feedback.users", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::feedbackUsers)
			.description("Latest feedback users count from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.useful.count", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::usefulCount)
			.description("Latest useful feedback count from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.noise.count", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::noiseCount)
			.description("Latest noise feedback count from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.anxious.count", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::anxiousCount)
			.description("Latest anxious feedback count from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.d1.cohort.size", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::d1CohortSize)
			.description("Latest D1 cohort size from daily product report")
			.register(meterRegistry);
		Gauge.builder("press.product.report.d7.cohort.size", productReportSnapshotGaugeValues, ProductReportSnapshotGaugeValues::d7CohortSize)
			.description("Latest D7 cohort size from daily product report")
			.register(meterRegistry);
	}

	private String errorTag(final Throwable throwable) {
		if (throwable == null) {
			return "UNKNOWN";
		}
		final var simpleName = throwable.getClass().getSimpleName();
		return simpleName == null || simpleName.isBlank() ? throwable.getClass().getName() : simpleName;
	}

	private static final class ProductReportSnapshotGaugeValues {

		private volatile double d1RetentionPct;
		private volatile double d7RetentionPct;
		private volatile double usefulRatePct;
		private volatile double noiseRatePct;
		private volatile double feedbackCtrPct;
		private volatile double premiumIntentPct;
		private volatile double deliveryUsers;
		private volatile double feedbackUsers;
		private volatile double usefulCount;
		private volatile double noiseCount;
		private volatile double anxiousCount;
		private volatile double d1CohortSize;
		private volatile double d7CohortSize;

		private void update(
			final double d1RetentionPct,
			final double d7RetentionPct,
			final double usefulRatePct,
			final double noiseRatePct,
			final double feedbackCtrPct,
			final double premiumIntentPct,
			final int deliveryUsers,
			final int feedbackUsers,
			final int usefulCount,
			final int noiseCount,
			final int anxiousCount,
			final int d1CohortSize,
			final int d7CohortSize
		) {
			this.d1RetentionPct = percentOrZero(d1RetentionPct);
			this.d7RetentionPct = percentOrZero(d7RetentionPct);
			this.usefulRatePct = percentOrZero(usefulRatePct);
			this.noiseRatePct = percentOrZero(noiseRatePct);
			this.feedbackCtrPct = percentOrZero(feedbackCtrPct);
			this.premiumIntentPct = percentOrZero(premiumIntentPct);
			this.deliveryUsers = nonNegativeOrZero(deliveryUsers);
			this.feedbackUsers = nonNegativeOrZero(feedbackUsers);
			this.usefulCount = nonNegativeOrZero(usefulCount);
			this.noiseCount = nonNegativeOrZero(noiseCount);
			this.anxiousCount = nonNegativeOrZero(anxiousCount);
			this.d1CohortSize = nonNegativeOrZero(d1CohortSize);
			this.d7CohortSize = nonNegativeOrZero(d7CohortSize);
		}

		private double d1RetentionPct() {
			return d1RetentionPct;
		}

		private double d7RetentionPct() {
			return d7RetentionPct;
		}

		private double usefulRatePct() {
			return usefulRatePct;
		}

		private double noiseRatePct() {
			return noiseRatePct;
		}

		private double feedbackCtrPct() {
			return feedbackCtrPct;
		}

		private double premiumIntentPct() {
			return premiumIntentPct;
		}

		private double deliveryUsers() {
			return deliveryUsers;
		}

		private double feedbackUsers() {
			return feedbackUsers;
		}

		private double usefulCount() {
			return usefulCount;
		}

		private double noiseCount() {
			return noiseCount;
		}

		private double anxiousCount() {
			return anxiousCount;
		}

		private double d1CohortSize() {
			return d1CohortSize;
		}

		private double d7CohortSize() {
			return d7CohortSize;
		}

		private double percentOrZero(final double value) {
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				return 0.0;
			}
			return Math.max(0.0, Math.min(100.0, value));
		}

		private double nonNegativeOrZero(final int value) {
			return Math.max(0, value);
		}
	}
}
