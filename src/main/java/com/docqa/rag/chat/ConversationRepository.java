package com.docqa.rag.chat;

import com.docqa.rag.retrieval.RetrievedChunk;
import com.docqa.rag.tenant.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations, turns and citations.
 *
 * <p>Same rule as everywhere else: every statement is tenant-scoped. Note in
 * particular {@link #findMessages} - it filters on {@code m.tenant_id} even
 * though it already filters on {@code conversation_id}, which is redundant
 * given the composite foreign key. Redundant defences at the query level cost
 * nothing and mean a future migration that relaxes a constraint cannot silently
 * open a hole.
 */
@Repository
public class ConversationRepository {

    private static final int SNIPPET_CHARS = 400;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Conversation(UUID id, TenantId tenantId, @Nullable String title,
                               Instant createdAt, Instant lastMessageAt) {}

    public Conversation create(TenantId tenantId, @Nullable String title) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id, tenant_id, title) VALUES (:id, :tenantId, :title)")
                .param("id", id)
                .param("tenantId", tenantId.value())
                .param("title", trim(title, 200))
                .update();
        Instant now = Instant.now();
        return new Conversation(id, tenantId, trim(title, 200), now, now);
    }

    public Optional<Conversation> find(TenantId tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, title, created_at, last_message_at
                  FROM conversations WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId.value())
                .param("id", id)
                .query((rs, rowNum) -> new Conversation(
                        rs.getObject("id", UUID.class),
                        new TenantId(rs.getString("tenant_id")),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_message_at").toInstant()))
                .optional();
    }

    /**
     * Appends a user turn and the assistant turn that answered it, with the
     * citations, in one transaction.
     *
     * <p>Written as one unit because a half-written exchange is worse than none:
     * a user message with no answer would be replayed as history on the next
     * turn and the model would see a question that was apparently ignored.
     */
    @Transactional
    public UUID appendExchange(TenantId tenantId,
                               UUID conversationId,
                               String question,
                               int questionTokens,
                               String answer,
                               int answerTokens,
                               @Nullable String model,
                               @Nullable Integer latencyMs,
                               boolean refused,
                               List<RetrievedChunk> sources) {

        insertMessage(tenantId, conversationId, ConversationMessage.MessageRole.USER,
                question, questionTokens, null, null, false);

        UUID assistantMessageId = insertMessage(tenantId, conversationId,
                ConversationMessage.MessageRole.ASSISTANT, answer, answerTokens,
                model, latencyMs, refused);

        insertSources(assistantMessageId, sources);

        jdbc.sql("""
                UPDATE conversations
                   SET last_message_at = now(),
                       title = COALESCE(title, :title)
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                // First question doubles as the conversation title, which makes
                // a conversation list readable without an extra model call.
                .param("title", trim(question, 200))
                .param("tenantId", tenantId.value())
                .param("id", conversationId)
                .update();

        return assistantMessageId;
    }

    private UUID insertMessage(TenantId tenantId,
                               UUID conversationId,
                               ConversationMessage.MessageRole role,
                               String content,
                               int tokenCount,
                               @Nullable String model,
                               @Nullable Integer latencyMs,
                               boolean refused) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO messages (id, conversation_id, tenant_id, role, content,
                                      token_count, model, latency_ms, refused)
                VALUES (:id, :conversationId, :tenantId, :role, :content,
                        :tokenCount, :model, :latencyMs, :refused)
                """)
                .param("id", id)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId.value())
                .param("role", role.name())
                .param("content", content)
                .param("tokenCount", tokenCount)
                .param("model", model)
                .param("latencyMs", latencyMs)
                .param("refused", refused)
                .update();
        return id;
    }

    private void insertSources(UUID messageId, List<RetrievedChunk> sources) {
        if (sources.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO message_sources (id, message_id, chunk_id, document_id,
                                             document_title, page_number, similarity_score,
                                             snippet, rank_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sources.stream().map(chunk -> new Object[]{
                        UUID.randomUUID(),
                        messageId,
                        chunk.chunkId(),
                        chunk.documentId(),
                        chunk.documentTitle(),
                        chunk.pageNumber(),
                        (float) chunk.similarity(),
                        chunk.snippet(SNIPPET_CHARS),
                        sources.indexOf(chunk) + 1
                }).toList(),
                new int[]{Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER,
                          Types.VARCHAR, Types.INTEGER, Types.REAL, Types.VARCHAR, Types.INTEGER});
    }

    /**
     * Most recent turns first.
     *
     * <p>Returned newest-first on purpose: the history budget is spent from the
     * most recent turn backwards, so fetching in that order lets the caller stop
     * reading as soon as the budget is gone rather than loading the whole
     * conversation and discarding the front of it.
     */
    public List<ConversationMessage> findRecentMessages(TenantId tenantId,
                                                        UUID conversationId,
                                                        int limit) {
        return jdbc.sql("""
                SELECT id, role, content, token_count, model, latency_ms, refused, created_at
                  FROM messages
                 WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                 ORDER BY seq DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId.value())
                .param("conversationId", conversationId)
                .param("limit", limit)
                .query(ConversationRepository::mapMessage)
                .list();
    }

    /** Full history, oldest first, with citations attached. Serves FR-7's GET endpoint. */
    public List<ConversationMessage> findMessages(TenantId tenantId, UUID conversationId) {
        List<ConversationMessage> messages = jdbc.sql("""
                SELECT id, role, content, token_count, model, latency_ms, refused, created_at
                  FROM messages
                 WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                 ORDER BY seq ASC
                """)
                .param("tenantId", tenantId.value())
                .param("conversationId", conversationId)
                .query(ConversationRepository::mapMessage)
                .list();

        if (messages.isEmpty()) {
            return messages;
        }

        // One extra query for all sources rather than one per message.
        Map<UUID, List<ConversationMessage.StoredSource>> sourcesByMessage = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT ms.message_id, ms.chunk_id, ms.document_id, ms.document_title,
                       ms.page_number, ms.similarity_score, ms.snippet, ms.rank_position
                  FROM message_sources ms
                  JOIN messages m ON m.id = ms.message_id
                 WHERE m.tenant_id = :tenantId AND m.conversation_id = :conversationId
                 ORDER BY ms.message_id, ms.rank_position
                """)
                .param("tenantId", tenantId.value())
                .param("conversationId", conversationId)
                .query((ResultSet rs, int rowNum) -> {
                    UUID messageId = rs.getObject("message_id", UUID.class);
                    sourcesByMessage.computeIfAbsent(messageId, key -> new ArrayList<>())
                            .add(new ConversationMessage.StoredSource(
                                    rs.getObject("chunk_id", UUID.class),
                                    rs.getObject("document_id", UUID.class),
                                    rs.getString("document_title"),
                                    (Integer) rs.getObject("page_number"),
                                    rs.getFloat("similarity_score"),
                                    rs.getString("snippet"),
                                    rs.getInt("rank_position")));
                    return messageId;
                })
                .list();

        return messages.stream()
                .map(message -> new ConversationMessage(
                        message.id(), message.role(), message.content(), message.tokenCount(),
                        message.model(), message.latencyMs(), message.refused(),
                        message.createdAt(),
                        sourcesByMessage.getOrDefault(message.id(), List.of())))
                .toList();
    }

    private static ConversationMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationMessage(
                rs.getObject("id", UUID.class),
                ConversationMessage.MessageRole.valueOf(rs.getString("role")),
                rs.getString("content"),
                (Integer) rs.getObject("token_count"),
                rs.getString("model"),
                (Integer) rs.getObject("latency_ms"),
                rs.getBoolean("refused"),
                rs.getTimestamp("created_at").toInstant(),
                List.of());
    }

    private static @Nullable String trim(@Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max - 1) + "…";
    }
}
