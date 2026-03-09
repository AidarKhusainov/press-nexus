package com.nexus.press.app.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractArtifactsContractTest {

	@Test
	void openApiMustContainCriticalPaths() throws IOException {
		final var root = projectRoot();
		final var openApi = root.resolve("docs/contracts/openapi.yaml");

		assertTrue(Files.exists(openApi), "OpenAPI file must exist");
		final var text = Files.readString(openApi);

		assertTrue(text.contains("/api/brief/daily"));
		assertTrue(text.contains("/api/feedback"));
		assertTrue(text.contains("/api/telegram/webhook"));
	}

	@Test
	void eventSchemasMustBeVersioned() throws IOException {
		final var root = projectRoot();
		final var feedbackSchema = root.resolve("docs/contracts/events/feedback-event-v1.schema.json");
		final var webhookSchema = root.resolve("docs/contracts/events/telegram-webhook-update-v1.schema.json");

		assertTrue(Files.exists(feedbackSchema), "Feedback schema must exist");
		assertTrue(Files.exists(webhookSchema), "Webhook schema must exist");

		assertTrue(Files.readString(feedbackSchema).contains("\"const\": \"v1\""));
		assertTrue(Files.readString(webhookSchema).contains("\"const\": \"v1\""));
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
