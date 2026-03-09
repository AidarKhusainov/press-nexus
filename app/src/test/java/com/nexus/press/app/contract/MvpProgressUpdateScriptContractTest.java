package com.nexus.press.app.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
