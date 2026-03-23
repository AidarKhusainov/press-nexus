package com.nexus.press.app.analytics.persistence.query;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReportQuerySqlContractTest {

	@Test
	void deliveredUserTopicsSqlMustUseTopicSlugColumn() throws Exception {
		final String source = Files.readString(sourceFile());

		assertTrue(source.contains("string_agg(ut.topic_slug, ',' ORDER BY ut.topic_slug)"));
		assertFalse(source.contains("string_agg(ut.topic, ',' ORDER BY ut.topic)"));
	}

	private Path sourceFile() {
		return projectRoot()
			.resolve("app/src/main/java/com/nexus/press/app/analytics/persistence/query/ProductReportQuery.java");
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
