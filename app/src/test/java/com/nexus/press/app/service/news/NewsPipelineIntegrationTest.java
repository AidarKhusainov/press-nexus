package com.nexus.press.app.service.news;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import io.r2dbc.spi.ConnectionFactories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nexus.press.app.config.WebClientConfig;
import com.nexus.press.app.config.property.HttpClientName;
import com.nexus.press.app.config.property.HttpClientProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.service.news.model.Media;
import com.nexus.press.app.service.news.model.RawNews;
import com.nexus.press.app.service.news.platform.GenericPopulateContentProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "db.tests", matches = "true")
class NewsPipelineIntegrationTest {

	private static final String SQL_STATE_INVALID_CATALOG_NAME = "3D000";
	private static final String TEST_DB_USER = System.getenv().getOrDefault("PRESS_TEST_DB_USER", "pressnexus");
	private static final String TEST_DB_PASSWORD = System.getenv().getOrDefault("PRESS_TEST_DB_PASSWORD", "");
	private static final String TEST_DB_NAME = System.getenv().getOrDefault("PRESS_TEST_DB_NAME", "pressnexus_test");
	private static final String TEST_DB_URL =
		"r2dbc:postgresql://%s:%s@localhost:5432/%s".formatted(TEST_DB_USER, TEST_DB_PASSWORD, TEST_DB_NAME);
	private static final String TEST_JDBC_ADMIN_URL =
		"jdbc:postgresql://localhost:5432/postgres";
	private static final String TEST_JDBC_URL =
		"jdbc:postgresql://localhost:5432/%s".formatted(TEST_DB_NAME);
	private static final AppMetrics APP_METRICS = new AppMetrics(new SimpleMeterRegistry());
	private static final Object DB_INIT_LOCK = new Object();

	private static volatile boolean testDatabaseReady;

	private DatabaseClient db;

	@BeforeEach
	void setUp() throws Exception {
		ensureTestDatabaseReady();
		db = DatabaseClient.create(ConnectionFactories.get(TEST_DB_URL));
		resetNewsTable();
	}

	@Test
	void pipelineFetchesContentAndSavesNewsIntoDatabase() throws Exception {
		final var p1 = "Integration paragraph one for extraction and persistence. ".repeat(12);
		final var p2 = "Integration paragraph two confirms complete text handling. ".repeat(10);
		final var html = "<html><body><article><p>" + p1 + "</p><p>" + p2 + "</p></article></body></html>";

		final var persistenceService = new NewsPersistenceService(db);
		final var processor = new GenericPopulateContentProcessor(stubWebClientConfig((request) -> okResponse(html)));
		final var populateService = new NewsPopulateContentService(List.of(processor), new NewsContentCleaner(), persistenceService, APP_METRICS);

		final var id = "it-" + UUID.randomUUID();
		final var raw = RawNews.builder()
			.id(id)
			.link("https://example.com/article")
			.title("Integration title")
			.description("fallback text")
			.source(Media.BBC)
			.publishedDate(OffsetDateTime.parse("2026-02-01T12:00:00Z"))
			.language("en")
			.build();

		final var saved = populateService.populate(raw).block(Duration.ofSeconds(10));
		assertNotNull(saved);
		assertTrue(saved.getRawContent().contains("Integration paragraph one"));
		assertTrue(saved.getRawContent().contains("Integration paragraph two"));
		assertEquals(saved.getRawContent(), saved.getCleanContent());

		final var row = db.sql("""
			SELECT media, url, content_raw, content_clean, status_content, status_embedding, status_summary
			FROM news
			WHERE id = :id
			""")
			.bind("id", id)
			.map((r, md) -> new PersistedNews(
				r.get("media", String.class),
				r.get("url", String.class),
				r.get("content_raw", String.class),
				r.get("content_clean", String.class),
				r.get("status_content", String.class),
				r.get("status_embedding", String.class),
				r.get("status_summary", String.class)
			))
			.one()
			.block(Duration.ofSeconds(5));

		assertNotNull(row);
		assertEquals("BBC", row.media());
		assertEquals("https://example.com/article", row.url());
		assertTrue(row.contentRaw().contains("Integration paragraph one"));
		assertEquals(row.contentRaw(), row.contentClean());
		assertEquals("DONE", row.statusContent());
		assertEquals("PENDING", row.statusEmbedding());
		assertEquals("PENDING", row.statusSummary());
	}

	@Test
	void claimContentUsesStageLeaseAndDoesNotRetryFailedRows() {
		insertNewsRow("pending-1", "PENDING", null);
		insertNewsRow("failed-1", "FAILED", null);
		insertNewsRow("active-1", "IN_PROGRESS", OffsetDateTime.now().minusMinutes(5));
		insertNewsRow("stale-1", "IN_PROGRESS", OffsetDateTime.now().minusHours(2));

		db.sql("UPDATE news SET updated_at = now() WHERE id = 'stale-1'")
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));

		final var claimedIds = new NewsPersistenceService(db)
			.claimNewsPendingContent(10, Duration.ofMinutes(30))
			.map(RawNews::getId)
			.collectList()
			.block(Duration.ofSeconds(5));

		assertNotNull(claimedIds);
		assertEquals(List.of("pending-1", "stale-1"), claimedIds);
		assertFalse(claimedIds.contains("failed-1"));
		assertFalse(claimedIds.contains("active-1"));

		final var snapshot = new NewsPersistenceService(db)
			.loadPipelineBacklog()
			.block(Duration.ofSeconds(5));
		assertNotNull(snapshot);
		assertEquals(1L, snapshot.contentFailed());
		assertEquals(3L, snapshot.totalOutstanding());
	}

	@Test
	void loadPipelineBacklogUsesStatusColumnsForAllStages() {
		insertNewsStageRow("content-pending", "PENDING", "PENDING", "PENDING", null, null);
		insertNewsStageRow("content-in-progress", "IN_PROGRESS", "PENDING", "PENDING", null, null);
		insertNewsStageRow("content-failed", "FAILED", "PENDING", "PENDING", null, null);
		insertNewsStageRow("embedding-pending", "DONE", "PENDING", "PENDING", "clean embedding", null);
		insertNewsStageRow("embedding-in-progress", "DONE", "IN_PROGRESS", "PENDING", "clean embedding", null);
		insertNewsStageRow("embedding-failed", "DONE", "FAILED", "PENDING", "clean embedding", null);
		insertNewsStageRow("summary-pending", "DONE", "DONE", "PENDING", "clean summary", null);
		insertEmbedding("summary-pending");
		insertNewsStageRow("summary-in-progress", "DONE", "DONE", "IN_PROGRESS", "clean summary", null);
		insertEmbedding("summary-in-progress");
		insertNewsStageRow("summary-failed", "DONE", "DONE", "FAILED", "clean summary", null);
		insertEmbedding("summary-failed");
		insertNewsStageRow("embedding-done-without-vector", "DONE", "DONE", "DONE", "clean no vector", null);

		final var snapshot = new NewsPersistenceService(db)
			.loadPipelineBacklog()
			.block(Duration.ofSeconds(5));

		assertNotNull(snapshot);
		assertEquals(1L, snapshot.contentPending());
		assertEquals(1L, snapshot.contentInProgress());
		assertEquals(1L, snapshot.contentFailed());
		assertEquals(2L, snapshot.embeddingPending());
		assertEquals(1L, snapshot.embeddingInProgress());
		assertEquals(1L, snapshot.embeddingFailed());
		assertEquals(1L, snapshot.summaryPending());
		assertEquals(1L, snapshot.summaryInProgress());
		assertEquals(1L, snapshot.summaryFailed());
	}

	@Test
	void claimSummarySkipsImmatureNewsUntilMaturityWindowPasses() {
		insertNewsStageRow("summary-old", "DONE", "DONE", "PENDING", "clean old", null);
		insertNewsStageRow("summary-fresh", "DONE", "DONE", "PENDING", "clean fresh", null);

		db.sql("UPDATE news SET published_at = :publishedAt WHERE id = :id")
			.bind("publishedAt", OffsetDateTime.now().minusMinutes(30))
			.bind("id", "summary-old")
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));
		db.sql("UPDATE news SET published_at = :publishedAt WHERE id = :id")
			.bind("publishedAt", OffsetDateTime.now().minusMinutes(5))
			.bind("id", "summary-fresh")
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));

		final var claimedIds = new NewsPersistenceService(db)
			.claimNewsPendingSummary(10, Duration.ofMinutes(30), Duration.ofMinutes(15))
			.map(RawNews::getId)
			.collectList()
			.block(Duration.ofSeconds(5));

		assertNotNull(claimedIds);
		assertEquals(List.of("summary-old"), claimedIds);
	}

	@Test
	void findReusableSummaryRequiresExactLanguageMatch() {
		insertNewsStageRow("summary-cache", "DONE", "DONE", "DONE", "clean summary", null);

		final var persistenceService = new NewsPersistenceService(db);
		persistenceService.saveNewsSummary("summary-cache", "GEMINI:model", "ru", "Russian summary", null)
			.block(Duration.ofSeconds(5));

		final var exact = persistenceService.findReusableSummary("summary-cache", "hash-summary-cache", "ru")
			.block(Duration.ofSeconds(5));
		final var mismatch = persistenceService.findReusableSummary(null, "hash-summary-cache", "es")
			.block(Duration.ofSeconds(5));

		assertNotNull(exact);
		assertEquals("Russian summary", exact.summary());
		assertNull(mismatch);
	}

	private void resetNewsTable() {
		db.sql("TRUNCATE TABLE news CASCADE")
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));
	}

	private void insertNewsRow(final String id, final String statusContent, final OffsetDateTime contentClaimedAt) {
		var spec = db.sql("""
			INSERT INTO news (
				id, media, url, title, fetched_at, content_raw, content_clean, content_hash,
				status_content, status_embedding, status_summary, content_claimed_at
			) VALUES (
				:id, 'BBC', :url, :title, now(), :content, :content, :contentHash,
				:statusContent, 'PENDING', 'PENDING', :contentClaimedAt
			)
			""")
			.bind("id", id)
			.bind("url", "https://example.com/" + id)
			.bind("title", "Title " + id)
			.bind("content", "Content " + id)
			.bind("contentHash", "hash-" + id)
			.bind("statusContent", statusContent);
		spec = contentClaimedAt != null
			? spec.bind("contentClaimedAt", contentClaimedAt)
			: spec.bindNull("contentClaimedAt", OffsetDateTime.class);
		spec.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));
	}

	private void insertNewsStageRow(
		final String id,
		final String statusContent,
		final String statusEmbedding,
		final String statusSummary,
		final String cleanContent,
		final OffsetDateTime embeddingClaimedAt
	) {
		var spec = db.sql("""
			INSERT INTO news (
				id, media, url, title, fetched_at, content_raw, content_clean, content_hash,
				status_content, status_embedding, status_summary, embedding_claimed_at
			) VALUES (
				:id, 'BBC', :url, :title, now(), :rawContent, :cleanContent, :contentHash,
				:statusContent, :statusEmbedding, :statusSummary, :embeddingClaimedAt
			)
			""")
			.bind("id", id)
			.bind("url", "https://example.com/" + id)
			.bind("title", "Title " + id)
			.bind("rawContent", "Raw " + id)
			.bind("contentHash", "hash-" + id)
			.bind("statusContent", statusContent)
			.bind("statusEmbedding", statusEmbedding)
			.bind("statusSummary", statusSummary);
		spec = cleanContent != null
			? spec.bind("cleanContent", cleanContent)
			: spec.bindNull("cleanContent", String.class);
		spec = embeddingClaimedAt != null
			? spec.bind("embeddingClaimedAt", embeddingClaimedAt)
			: spec.bindNull("embeddingClaimedAt", OffsetDateTime.class);
		spec.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(5));
	}

	private void insertEmbedding(final String id) {
		final float[] embedding = new float[768];
		embedding[0] = 1.0f;
		new PostgresReactiveNewsSimilarityStore(db)
			.upsertEmbedding(id, embedding)
			.block(Duration.ofSeconds(5));
	}

	private static void ensureTestDatabaseReady() throws Exception {
		if (testDatabaseReady) {
			return;
		}

		synchronized (DB_INIT_LOCK) {
			if (testDatabaseReady) {
				return;
			}

			validateDatabaseName();
			if (!databaseExists()) {
				createDatabaseIfMissing();
			}
			applyMigrations();
			testDatabaseReady = true;
		}
	}

	private static void validateDatabaseName() {
		if (!TEST_DB_NAME.matches("[A-Za-z0-9_]+")) {
			throw new IllegalStateException("Unsupported test database name: " + TEST_DB_NAME);
		}
	}

	private static void createDatabaseIfMissing() throws Exception {
		try (
			var connection = DriverManager.getConnection(TEST_JDBC_ADMIN_URL, TEST_DB_USER, TEST_DB_PASSWORD);
			var existsStatement = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?");
		) {
			existsStatement.setString(1, TEST_DB_NAME);
			try (var result = existsStatement.executeQuery()) {
				if (result.next()) {
					return;
				}
			}

			try (var statement = connection.createStatement()) {
				statement.execute("CREATE DATABASE " + TEST_DB_NAME);
			}
		}
	}

	private static boolean databaseExists() throws Exception {
		try (var ignored = DriverManager.getConnection(TEST_JDBC_URL, TEST_DB_USER, TEST_DB_PASSWORD)) {
			return true;
		} catch (SQLException ex) {
			if (SQL_STATE_INVALID_CATALOG_NAME.equals(ex.getSQLState())) {
				return false;
			}
			throw ex;
		}
	}

	private static void applyMigrations() throws Exception {
		try (var connection = DriverManager.getConnection(TEST_JDBC_URL, TEST_DB_USER, TEST_DB_PASSWORD)) {
			final var database = DatabaseFactory.getInstance()
				.findCorrectDatabaseImplementation(new JdbcConnection(connection));
			final var resources = projectRoot().resolve("app/src/main/resources");

			try (var accessor = new DirectoryResourceAccessor(resources)) {
				final var liquibase = new Liquibase(
					"db/changelog/db.changelog-master.json",
					accessor,
					database
				);
				liquibase.update(new Contexts(), new LabelExpression());
			}
		}
	}

	private static Path projectRoot() {
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

	private static HttpClientProperties defaultProperties() {
		final var cfg = new HttpClientProperties.ClientConfig(
			"https://example.com",
			new HttpClientProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(2)),
			new HttpClientProperties.Retry(1, Duration.ofMillis(10), 0.0)
		);
		return new HttpClientProperties(Map.of(HttpClientName.NEWS, cfg));
	}

	private static WebClientConfig stubWebClientConfig(final ExchangeFunction exchangeFunction) {
		final var webClient = WebClient.builder()
			.exchangeFunction(exchangeFunction)
			.build();
		return new WebClientConfig(defaultProperties(), APP_METRICS) {
			@Override
			public WebClient getWebClient(final HttpClientName clientName) {
				return webClient;
			}
		};
	}

	private static Mono<ClientResponse> okResponse(final String body) {
		return Mono.just(
			ClientResponse.create(HttpStatusCode.valueOf(200))
				.header("Content-Type", "text/html; charset=utf-8")
				.body(body)
				.build()
		);
	}

	private record PersistedNews(
		String media,
		String url,
		String contentRaw,
		String contentClean,
		String statusContent,
		String statusEmbedding,
		String statusSummary
	) {}
}
