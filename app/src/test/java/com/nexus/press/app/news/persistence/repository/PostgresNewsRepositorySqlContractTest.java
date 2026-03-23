package com.nexus.press.app.news.persistence.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresNewsRepositorySqlContractTest {

	@Test
	void sourceMustKeepExactLanguageSummaryLookupAndConflictKeys() throws Exception {
		final String source = Files.readString(sourceFile());

		assertTrue(source.contains("AND s.lang = :lang"));
		assertTrue(source.contains("ON CONFLICT (news_id, model, lang) DO UPDATE"));
		assertTrue(source.contains("ON CONFLICT ON CONSTRAINT news_url_uq DO UPDATE"));
		assertTrue(source.contains("ON CONFLICT DO NOTHING"));
	}

	private Path sourceFile() {
		return projectRoot()
			.resolve("app/src/main/java/com/nexus/press/app/news/persistence/repository/PostgresNewsRepository.java");
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
