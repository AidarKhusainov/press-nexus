package com.nexus.press.app.contract;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClosedBetaCheckScriptContractTest {

	@Test
	void scriptMustUseTimeoutAndRetryForHttpCalls() throws IOException {
		final var script = Files.readString(projectRoot().resolve("scripts/closed-beta-check.sh"));

		assertTrue(script.contains("--connect-timeout"), "Script must define an HTTP connect timeout");
		assertTrue(script.contains("--max-time"), "Script must define an HTTP max timeout");
		assertTrue(script.contains("--retry"), "Script must retry transient HTTP failures");
		assertTrue(script.contains("--retry-all-errors"), "Script must retry all transient curl errors");
	}

	@Test
	void scriptFetchesPreviewTriggersDeliveryAndPrintsReport(@TempDir final Path tempDir) throws Exception {
		assumeTrue(commandAvailable("jq"), "jq is required for the script contract test");

		final AtomicBoolean sendTriggered = new AtomicBoolean(false);
		final HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(0), 0);
		} catch (final SocketException exception) {
			assumeTrue(false, "local HttpServer sockets are not permitted in this environment");
			return;
		}
		server.createContext("/api/brief/daily/text", exchange -> {
			final byte[] body = """
				Press Nexus Lite
				
				1. Preview card
				Что случилось: кратко.
				Почему важно: контекст.
				Что дальше: следующий шаг.
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/api/brief/daily/send", exchange -> {
			sendTriggered.set(true);
			final byte[] body = "{\"sentChats\":2}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/api/analytics/product-report/daily", exchange -> {
			final byte[] body = """
				{
				  "reportDate": "2026-03-09",
				  "deliveryUsers": 12,
				  "feedbackUsers": 7,
				  "usefulRatePct": 71.4,
				  "noiseRatePct": 14.3,
				  "d1RetentionPct": 41.7,
				  "d7RetentionPct": 33.3
				}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		final Path previewOut = tempDir.resolve("preview.txt");
		final Path reportOut = tempDir.resolve("report.json");
		try {
			final Process process = new ProcessBuilder(
				"/bin/bash",
				projectRoot().resolve("scripts/closed-beta-check.sh").toString(),
				"--api-base-url",
				"http://127.0.0.1:" + server.getAddress().getPort(),
				"--report-date",
				"2026-03-09",
				"--trigger-send-now",
				"--preview-out",
				previewOut.toString(),
				"--report-json-out",
				reportOut.toString()
			)
				.directory(projectRoot().toFile())
				.redirectErrorStream(true)
				.start();

			final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(0, process.waitFor(), () -> "Script failed:\n" + output);
			assertTrue(output.contains("Closed beta preview"));
			assertTrue(output.contains("Delivery trigger sentChats: 2"));
			assertTrue(output.contains("reportDate: 2026-03-09"));
			assertTrue(output.contains("usefulRatePct: 71.4"));
		} finally {
			server.stop(0);
		}

		assertTrue(sendTriggered.get(), "Script must trigger POST /api/brief/daily/send when requested");
		assertTrue(Files.readString(previewOut).contains("Preview card"));
		assertTrue(Files.readString(reportOut).contains("\"deliveryUsers\": 12"));
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
}
