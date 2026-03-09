package com.nexus.press.app.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

	private final JavaClasses importedClasses = new ClassFileImporter()
		.withImportOption(new ImportOption.DoNotIncludeTests())
		.importPackages("com.nexus.press.app");

	@Test
	void serviceLayerMustNotDependOnWebLayer() {
		noClasses().that().resideInAPackage("..service..")
			.should().dependOnClassesThat().resideInAPackage("com.nexus.press.app.web..")
			.check(importedClasses);
	}

	@Test
	void schedulerMustNotDependOnWebLayer() {
		noClasses().that().resideInAPackage("..service.scheduler..")
			.should().dependOnClassesThat().resideInAPackage("com.nexus.press.app.web..")
			.check(importedClasses);
	}

	@Test
	void webLayerMustNotDependOnRepositoryEntitiesDirectly() {
		noClasses().that().resideInAPackage("..web..")
			.should().dependOnClassesThat().resideInAPackage("com.nexus.press.app.repository.entity..")
			.check(importedClasses);
	}
}
