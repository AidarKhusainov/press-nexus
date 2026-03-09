package com.nexus.press.app.contract;

import com.nexus.press.app.config.property.ProductReportProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReportSchedulerDefaultsContractTest {

	@Test
	void productReportPropertiesMustBeEnabledByDefault() {
		final var properties = new ProductReportProperties();

		assertTrue(properties.isEnabled());
	}

	@Test
	void applicationPropertiesMustKeepProductReportSchedulerEnabled() throws Exception {
		final String source = Files.readString(
			projectRoot().resolve("app/src/main/resources/application.properties")
		);

		assertTrue(source.contains("press.analytics.product-report.enabled=true"));
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
