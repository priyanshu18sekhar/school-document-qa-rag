package com.docqa.rag.testsupport;

import com.docqa.rag.document.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base class for integration tests: a real Postgres with a real pgvector
 * extension, and stubbed model providers.
 *
 * <p>The database is genuinely Postgres, not H2. That is not pedantry - H2 has
 * no {@code vector} type, no {@code <=>} operator, no HNSW index and no
 * {@code set_config}, so an H2 "integration" test would exercise none of the
 * code this system's correctness depends on. The retrieval query is the thing
 * most likely to break and it can only be tested against the engine that runs
 * it.
 *
 * <p>The container is a static field on the shared base class, so every
 * integration test reuses one Postgres for the whole run rather than paying
 * startup per class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubModelConfiguration.class)
@TestPropertySource(properties = {
        // No real provider beans are created, so no test can reach the network.
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        // Single worker so "upload then assert" is not a race between four.
        "rag.ingestion.worker-threads=1",
        "rag.retrieval.similarity-threshold=0.62",
        // Off by default in tests: the stub chat model returns canned text, so a
        // "rewrite" would be nonsense and every assertion about retrieval would
        // depend on it. QueryRewritingIT turns it back on and tests it directly.
        "rag.chat.query-rewriting-enabled=false",
        "logging.level.com.docqa.rag=DEBUG"
})
public abstract class AbstractPostgresIT {

    /**
     * Singleton container, started once for the whole test run.
     *
     * <p>Deliberately <em>not</em> managed by {@code @Testcontainers}/{@code @Container}:
     * that annotation pair stops a static container when its test class
     * finishes, and because this field is inherited, the second test class
     * would then connect to a dead Postgres and every test in it would fail on
     * the connection timeout. Starting it here and leaving it running is the
     * documented pattern for sharing one container across classes; Testcontainers'
     * Ryuk sidecar removes it when the JVM exits.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StubChatModel chatModel;
    @Autowired protected StubEmbeddingModel embeddingModel;

    protected RestClient http;

    @BeforeEach
    void resetState() {
        // Status handler disabled: these tests assert on 4xx and 5xx responses,
        // so an exception on non-2xx would be in the way.
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        // Truncate rather than recreate: migrations run once for the context,
        // and cascading from `documents` and `conversations` clears everything
        // else - which incidentally proves the cascade rules are wired up.
        jdbc.execute("TRUNCATE documents, conversations RESTART IDENTITY CASCADE");
        chatModel.reset();
        embeddingModel.reset();
    }

    // ---- helpers ----------------------------------------------------------

    protected ResponseEntity<Map> upload(String tenant,
                                         String filename,
                                         String content,
                                         String category) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (category != null) {
            form.add("category", category);
        }
        return http.post()
                .uri("/api/v1/documents")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toEntity(Map.class);
    }

    /** Uploads a document and blocks until ingestion reports READY. */
    protected UUID uploadAndWait(String tenant, String filename, String content, String category) {
        ResponseEntity<Map> response = upload(tenant, filename, content, category);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("upload of %s returned %s: %s",
                        filename, response.getStatusCode(), response.getBody())
                .isTrue();

        UUID documentId = UUID.fromString(String.valueOf(response.getBody().get("documentId")));
        awaitStatus(tenant, documentId, DocumentStatus.READY);
        return documentId;
    }

    protected void awaitStatus(String tenant, UUID documentId, DocumentStatus expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                // startsWith, not isEqualTo: statusOf appends the failure reason
                // so that a test waiting for READY that gets FAILED says why,
                // instead of just timing out.
                .untilAsserted(() -> assertThat(statusOf(tenant, documentId))
                        .as("document %s should reach %s", documentId, expected)
                        .startsWith(expected.name()));
    }

    /** Returns the status, or {@code FAILED: reason} so a failing test says why. */
    protected String statusOf(String tenant, UUID documentId) {
        ResponseEntity<Map> response = get("/api/v1/documents/" + documentId, tenant);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return "MISSING";
        }
        Object status = response.getBody().get("status");
        Object error = response.getBody().get("errorMessage");
        return "FAILED".equals(status) && error != null
                ? "FAILED: " + error
                : String.valueOf(status);
    }

    protected ResponseEntity<Map> get(String path, String tenant) {
        return http.get()
                .uri(path)
                .header("X-Tenant-Id", tenant)
                .retrieve()
                .toEntity(Map.class);
    }

    protected ResponseEntity<Map> ask(String tenant, String question, String category) {
        Map<String, Object> body = category == null
                ? Map.of("question", question)
                : Map.of("question", question, "category", category);
        return http.post()
                .uri("/api/v1/chat")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }

    protected ResponseEntity<Map> ask(String tenant, String question, String category,
                                      UUID conversationId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("question", question);
        body.put("conversationId", conversationId.toString());
        if (category != null) {
            body.put("category", category);
        }
        return http.post()
                .uri("/api/v1/chat")
                .header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }

    protected int chunkCountFor(UUID documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }
}
