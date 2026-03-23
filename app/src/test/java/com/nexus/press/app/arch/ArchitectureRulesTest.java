package com.nexus.press.app.arch;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureRulesTest {

	private static final String BASE_PACKAGE = "com.nexus.press.app";
	private static final List<String> FUTURE_MODULES = List.of("news", "brief", "profile", "telegram", "analytics", "feedback", "premium", "ai");
	private static final Set<String> LEGACY_EXACT_PACKAGES = Set.of(
		BASE_PACKAGE,
		BASE_PACKAGE + ".config",
		BASE_PACKAGE + ".config.property",
		BASE_PACKAGE + ".controller",
		BASE_PACKAGE + ".observability",
		BASE_PACKAGE + ".repository",
		BASE_PACKAGE + ".repository.entity",
		BASE_PACKAGE + ".service",
		BASE_PACKAGE + ".service.analytics",
		BASE_PACKAGE + ".service.delivery",
		BASE_PACKAGE + ".service.profile",
		BASE_PACKAGE + ".util",
		BASE_PACKAGE + ".web",
		BASE_PACKAGE + ".web.generated",
		BASE_PACKAGE + ".web.generated.api",
		BASE_PACKAGE + ".web.generated.model"
	);
	private static final Set<String> LEGACY_DATABASE_CLIENT_OWNERS = Set.of(
		BASE_PACKAGE + ".service.analytics.ProductReportService"
	);
	private static final String[] FUTURE_WEB_PACKAGES = futureModulePackagePatterns("web");
	private static final String[] FUTURE_JOB_PACKAGES = futureModulePackagePatterns("job");
	private static final String[] FUTURE_USECASE_PACKAGES = futureModulePackagePatterns("usecase");
	private static final String[] FUTURE_POLICY_PACKAGES = futureModulePackagePatterns("policy");
	private static final String[] FUTURE_PERSISTENCE_PACKAGES = futureModulePackagePatterns("persistence");
	private static final String[] FUTURE_PERSISTENCE_ENTITY_PACKAGES = futureModulePackagePatterns("persistence.entity");
	private static final String[] FUTURE_INTEGRATION_PACKAGES = futureModulePackagePatterns("integration");

	private final JavaClasses importedClasses = new ClassFileImporter()
		.withImportOption(new ImportOption.DoNotIncludeTests())
		.importPackages(BASE_PACKAGE);

	@Test
	void applicationPackagesMustBeKnown() {
		final List<String> unexpectedPackages = importedClasses.stream()
			.map(JavaClass::getPackageName)
			.filter(packageName -> packageName.startsWith(BASE_PACKAGE))
			.filter(packageName -> !isKnownPackage(packageName))
			.distinct()
			.sorted()
			.toList();

		assertTrue(
			unexpectedPackages.isEmpty(),
			() -> "Unknown application packages detected:\n" + String.join("\n", unexpectedPackages)
		);
	}

	@Test
	void serviceLayerMustNotDependOnWebLayer() {
		noClasses().that().resideInAPackage("..service..")
			.should().dependOnClassesThat().resideInAPackage(BASE_PACKAGE + ".web..")
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void schedulerMustNotDependOnWebLayer() {
		noClasses().that().resideInAnyPackage(concat(new String[] {"..service.scheduler.."}, FUTURE_JOB_PACKAGES))
			.should().dependOnClassesThat().resideInAPackage(BASE_PACKAGE + ".web..")
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void webEntryPointsMustNotDependOnPersistenceEntitiesDirectly() {
		noClasses().that().resideInAnyPackage(concat(new String[] {BASE_PACKAGE + ".controller..", BASE_PACKAGE + ".web.."}, FUTURE_WEB_PACKAGES))
			.should().dependOnClassesThat()
			.resideInAnyPackage(concat(new String[] {BASE_PACKAGE + ".repository.entity.."}, FUTURE_PERSISTENCE_ENTITY_PACKAGES))
			.check(importedClasses);
	}

	@Test
	void databaseClientMustStayInPersistenceOrApprovedLegacyClasses() {
		final List<String> violators = importedClasses.stream()
			.filter(javaClass -> dependsOn(javaClass, DatabaseClient.class.getName()))
			.map(JavaClass::getFullName)
			.filter(className -> !isApprovedDatabaseClientOwner(className))
			.sorted()
			.toList();

		assertTrue(
			violators.isEmpty(),
			() -> "DatabaseClient is only allowed in persistence packages or approved legacy classes:\n"
				+ String.join("\n", violators)
		);
	}

	@Test
	void futureUsecasePackagesMustNotDependOnDatabaseClient() {
		noClasses().that().resideInAnyPackage(FUTURE_USECASE_PACKAGES)
			.should().dependOnClassesThat().haveFullyQualifiedName(DatabaseClient.class.getName())
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void futureUsecasePackagesMustNotDependOnWebClientDirectly() {
		noClasses().that().resideInAnyPackage(FUTURE_USECASE_PACKAGES)
			.should().dependOnClassesThat().haveFullyQualifiedName(WebClient.class.getName())
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void futureWebAndJobPackagesMustNotDependOnPersistencePackages() {
		noClasses().that().resideInAnyPackage(concat(FUTURE_WEB_PACKAGES, FUTURE_JOB_PACKAGES))
			.should().dependOnClassesThat().resideInAnyPackage(FUTURE_PERSISTENCE_PACKAGES)
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void futurePolicyPackagesMustNotDependOnPersistenceOrIntegrationPackages() {
		noClasses().that().resideInAnyPackage(FUTURE_POLICY_PACKAGES)
			.should().dependOnClassesThat().resideInAnyPackage(FUTURE_PERSISTENCE_PACKAGES)
			.allowEmptyShould(true)
			.check(importedClasses);

		noClasses().that().resideInAnyPackage(FUTURE_POLICY_PACKAGES)
			.should().dependOnClassesThat().resideInAnyPackage(FUTURE_INTEGRATION_PACKAGES)
			.allowEmptyShould(true)
			.check(importedClasses);
	}

	@Test
	void futureUsecaseClassesMustExposeSinglePublicMethod() {
		final List<String> violations = importedClasses.stream()
			.filter(javaClass -> javaClass.getPackageName().contains(".usecase"))
			.filter(javaClass -> !javaClass.isInterface())
			.filter(javaClass -> !javaClass.isEnum())
			.filter(javaClass -> !javaClass.getFullName().contains("$"))
			.map(this::singlePublicMethodViolation)
			.filter(violation -> violation != null)
			.sorted()
			.toList();

		assertTrue(
			violations.isEmpty(),
			() -> "Use case classes must expose exactly one public method:\n" + String.join("\n", violations)
		);
	}

	private boolean isKnownPackage(final String packageName) {
		if (LEGACY_EXACT_PACKAGES.contains(packageName)) {
			return true;
		}

		for (final String prefix : futurePackagePrefixes()) {
			if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
				return true;
			}
		}

		return false;
	}

	private List<String> futurePackagePrefixes() {
		final List<String> prefixes = new ArrayList<>();
		prefixes.add(BASE_PACKAGE + ".shared");

		for (final String module : FUTURE_MODULES) {
			final String root = BASE_PACKAGE + "." + module;
			prefixes.add(root);
			prefixes.add(root + ".web");
			prefixes.add(root + ".usecase");
			prefixes.add(root + ".model");
			prefixes.add(root + ".policy");
			prefixes.add(root + ".persistence");
			prefixes.add(root + ".persistence.repository");
			prefixes.add(root + ".persistence.query");
			prefixes.add(root + ".persistence.entity");
			prefixes.add(root + ".persistence.mapper");
			prefixes.add(root + ".integration");
			prefixes.add(root + ".format");
			prefixes.add(root + ".job");
		}

		return prefixes;
	}

	private boolean dependsOn(final JavaClass source, final String targetFullName) {
		return source.getDirectDependenciesFromSelf().stream()
			.anyMatch(dependency -> dependency.getTargetClass().getFullName().equals(targetFullName));
	}

	private boolean isApprovedDatabaseClientOwner(final String className) {
		if (LEGACY_DATABASE_CLIENT_OWNERS.contains(className)) {
			return true;
		}

		return className.contains(".persistence.")
			|| className.startsWith(BASE_PACKAGE + ".repository.")
			|| className.equals(BASE_PACKAGE + ".repository");
	}

	private String singlePublicMethodViolation(final JavaClass javaClass) {
		final long publicDeclaredMethods = javaClass.reflect().getDeclaredMethods().length == 0
			? 0
			: java.util.Arrays.stream(javaClass.reflect().getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> !method.isSynthetic())
				.filter(method -> !method.isBridge())
				.count();

		if (publicDeclaredMethods == 1) {
			return null;
		}

		return javaClass.getFullName() + " -> public methods: " + publicDeclaredMethods;
	}

	private static String[] futureModulePackagePatterns(final String suffix) {
		return FUTURE_MODULES.stream()
			.map(module -> BASE_PACKAGE + "." + module + "." + suffix + "..")
			.toArray(String[]::new);
	}

	private static String[] concat(final String[] left, final String[] right) {
		final String[] result = new String[left.length + right.length];
		System.arraycopy(left, 0, result, 0, left.length);
		System.arraycopy(right, 0, result, left.length, right.length);
		return result;
	}
}
