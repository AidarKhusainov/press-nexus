package com.nexus.press.app.contract;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MvpProgressUpdateScriptContractTest {

	@Test
	void scriptMustUseTimeoutAndRetryForHttpFetch() throws IOException {
		final var script = Files.readString(projectRoot().resolve("scripts/update-mvp-progress-go-no-go.sh"));

		assertTrue(script.contains("--connect-timeout"), "Script must define an HTTP connect timeout");
		assertTrue(script.contains("--max-time"), "Script must define an HTTP max timeout");
		assertTrue(script.contains("--retry"), "Script must retry transient HTTP failures");
		assertTrue(script.contains("--retry-all-errors"), "Script must retry all transient curl errors");
	}

	@Test
	void scriptUpdatesGoNoGoMetricsFromProductReportJson(@TempDir final Path tempDir) throws Exception {
		assumeTrue(commandAvailable("jq"), "jq is required for the script contract test");

		final Path projectRoot = projectRoot();
		final Path sourceMvp = projectRoot.resolve("docs/MVP_PROGRESS.md");
		final Path targetMvp = tempDir.resolve("MVP_PROGRESS.md");
		Files.copy(sourceMvp, targetMvp);

		final Path reportJson = tempDir.resolve("product-report.json");
		Files.writeString(reportJson, """
			{
			  "reportDate": "2026-03-08",
			  "deliveryUsers": 80,
			  "feedbackEvents": 42,
			  "feedbackUsers": 35,
			  "usefulCount": 30,
			  "noiseCount": 8,
			  "anxiousCount": 2,
			  "usefulRatePct": 75.0,
			  "noiseRatePct": 20.0,
			  "premiumIntentEvents": 11,
			  "premiumIntentUsers": 9,
			  "premiumIntentPct": 11.3,
			  "d1CohortSize": 12,
			  "d1RetainedUsers": 5,
			  "d1RetentionPct": 41.7,
			  "d7CohortSize": 10,
			  "d7RetainedUsers": 4,
			  "d7RetentionPct": 40.0
			}
			""");

		final Process process = new ProcessBuilder(
			"/bin/bash",
			projectRoot.resolve("scripts/update-mvp-progress-go-no-go.sh").toString(),
			"--report-json-file",
			reportJson.toString(),
			"--target-file",
			targetMvp.toString(),
			"--today",
			"2026-03-09"
		)
			.directory(projectRoot.toFile())
			.redirectErrorStream(true)
			.start();

		final String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), () -> "Script failed:\n" + output);

		final String updated = Files.readString(targetMvp);
		assertTrue(updated.contains("Last updated: 2026-03-09"));
		assertTrue(updated.contains("| D7 retention | `>= 35%` | 40.0% (4/10, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Useful | `>= 70%` | 75.0% (30/40, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Noise | `<= 20%` | 20.0% (8/40, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Premium intent | `>= 10%` | 11.3% (9/80, 2026-03-08) | DONE |"));
	}

	@Test
	void scriptUpdatesGoNoGoMetricsFromPrometheusSnapshot(@TempDir final Path tempDir) throws Exception {
		assumeTrue(commandAvailable("jq"), "jq is required for the script contract test");

		final Path projectRoot = projectRoot();
		final Path sourceMvp = projectRoot.resolve("docs/MVP_PROGRESS.md");
		final Path targetMvp = tempDir.resolve("MVP_PROGRESS.md");
		Files.copy(sourceMvp, targetMvp);

		final Map<String, String> metricValues = Map.ofEntries(
			Map.entry("press_product_report_delivery_users", "80"),
			Map.entry("press_product_report_feedback_users", "35"),
			Map.entry("press_product_report_useful_count", "30"),
			Map.entry("press_product_report_noise_count", "8"),
			Map.entry("press_product_report_anxious_count", "2"),
			Map.entry("press_product_report_useful_rate_pct", "75.0"),
			Map.entry("press_product_report_noise_rate_pct", "20.0"),
			Map.entry("press_product_report_premium_intent_users", "9"),
			Map.entry("press_product_report_premium_intent_pct", "11.3"),
			Map.entry("press_product_report_d1_cohort_size", "12"),
			Map.entry("press_product_report_d1_retained_users", "5"),
			Map.entry("press_product_report_d1_retention_pct", "41.7"),
			Map.entry("press_product_report_d7_cohort_size", "10"),
			Map.entry("press_product_report_d7_retained_users", "4"),
			Map.entry("press_product_report_d7_retention_pct", "40.0")
		);

		final HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(0), 0);
		} catch (final SocketException exception) {
			assumeTrue(false, "local HttpServer sockets are not permitted in this environment");
			return;
		}
		server.createContext("/api/v1/query", exchange -> {
			final String query = decodedQuery(exchange.getRequestURI().getRawQuery());
			final String value = metricValues.entrySet().stream()
				.filter(entry -> query.contains(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
			final String response = value == null
				? "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"
				: """
					{"status":"success","data":{"resultType":"vector","result":[{"metric":{"application":"app"},"value":[1710000000,"%s"]}]}}
					""".formatted(value);
			final byte[] body = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		try {
			final Process process = new ProcessBuilder(
				"/bin/bash",
				projectRoot.resolve("scripts/update-mvp-progress-go-no-go.sh").toString(),
				"--date",
				"2026-03-08",
				"--prometheus-base-url",
				"http://127.0.0.1:" + server.getAddress().getPort(),
				"--target-file",
				targetMvp.toString(),
				"--today",
				"2026-03-09"
			)
				.directory(projectRoot.toFile())
				.redirectErrorStream(true)
				.start();

			final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(0, process.waitFor(), () -> "Script failed:\n" + output);
		} finally {
			server.stop(0);
		}

		final String updated = Files.readString(targetMvp);
		assertTrue(updated.contains("Last updated: 2026-03-09"));
		assertTrue(updated.contains("| D7 retention | `>= 35%` | 40.0% (4/10, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Useful | `>= 70%` | 75.0% (30/40, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Noise | `<= 20%` | 20.0% (8/40, 2026-03-08) | DONE |"));
		assertTrue(updated.contains("| Premium intent | `>= 10%` | 11.3% (9/80, 2026-03-08) | DONE |"));
	}

	private boolean commandAvailable(final String command) throws Exception {
		final Process process = new ProcessBuilder("/bin/bash", "-lc", "command -v " + command).start();
		return process.waitFor() == 0;
	}

	private Path projectRoot() {
		final var explicit = System.getProperty("maven.multiModuleProjectDirectory");
		if (explicit != null && !explicit.isBlank()) {
			return Path.of(explicit);
		}
		final var cwd = Path.of("").toAbsolutePath().normalize();
		if (cwd.getFileName() != null && "app".equals(cwd.getFileName().toString())) {
			return cwd.getParent();
		}
		return cwd;
	}

	private String decodedQuery(final String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return "";
		}
		for (final String part : rawQuery.split("&")) {
			if (part.startsWith("query=")) {
				return URLDecoder.decode(part.substring("query=".length()), StandardCharsets.UTF_8);
			}
		}
		return "";
	}
}
