package com.nexus.press.app.news.persistence.query;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresNewsPipelineQuerySqlContractTest {

	@Test
	void sourceMustKeepMaturityWindowAndEmbeddingBacklogFilters() throws Exception {
		final String source = Files.readString(sourceFile());

		assertTrue(source.contains("<= now() - (:maturitySeconds * interval '1 second')"));
		assertTrue(source.contains("NOT EXISTS (SELECT 1 FROM news_embedding e WHERE e.news_id = n.id)"));
		assertTrue(source.contains("n.status_summary IN ('PENDING', 'IN_PROGRESS')"));
	}

	private Path sourceFile() {
		return projectRoot()
			.resolve("app/src/main/java/com/nexus/press/app/news/persistence/query/PostgresNewsPipelineQuery.java");
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
