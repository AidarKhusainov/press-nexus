package com.nexus.press.app.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppMetricsTest {

	@Test
	void productReportSnapshotUpdatesGauges() {
		final var meterRegistry = new SimpleMeterRegistry();
		final var appMetrics = new AppMetrics(meterRegistry);

		appMetrics.productReportSnapshot(
			39.5,
			34.1,
			72.4,
			18.2,
			28.3,
			9.8,
			140,
			52,
			41,
			12,
			9,
			67,
			35
		);

		assertEquals(39.5, meterRegistry.get("press.product.report.d1.retention.pct").gauge().value(), 0.0001);
		assertEquals(34.1, meterRegistry.get("press.product.report.d7.retention.pct").gauge().value(), 0.0001);
		assertEquals(72.4, meterRegistry.get("press.product.report.useful.rate.pct").gauge().value(), 0.0001);
		assertEquals(18.2, meterRegistry.get("press.product.report.noise.rate.pct").gauge().value(), 0.0001);
		assertEquals(28.3, meterRegistry.get("press.product.report.feedback.ctr.pct").gauge().value(), 0.0001);
		assertEquals(9.8, meterRegistry.get("press.product.report.premium.intent.pct").gauge().value(), 0.0001);
		assertEquals(140.0, meterRegistry.get("press.product.report.delivery.users").gauge().value(), 0.0001);
		assertEquals(52.0, meterRegistry.get("press.product.report.feedback.users").gauge().value(), 0.0001);
		assertEquals(41.0, meterRegistry.get("press.product.report.useful.count").gauge().value(), 0.0001);
		assertEquals(12.0, meterRegistry.get("press.product.report.noise.count").gauge().value(), 0.0001);
		assertEquals(9.0, meterRegistry.get("press.product.report.anxious.count").gauge().value(), 0.0001);
		assertEquals(67.0, meterRegistry.get("press.product.report.d1.cohort.size").gauge().value(), 0.0001);
		assertEquals(35.0, meterRegistry.get("press.product.report.d7.cohort.size").gauge().value(), 0.0001);
	}
}
