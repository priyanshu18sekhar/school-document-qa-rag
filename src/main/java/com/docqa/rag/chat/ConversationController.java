package com.docqa.rag.chat;

import com.docqa.rag.chat.dto.SourceDto;
import com.docqa.rag.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Conversation history (FR-7). */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "Stored question and answer history")
public class ConversationController {

    private final ConversationRepository conversations;

    public ConversationController(ConversationRepository conversations) {
        this.conversations = conversations;
    }

    public record ConversationResponse(
            UUID id,
            @Nullable String title,
            Instant createdAt,
            Instant lastMessageAt,
            List<Turn> messages
    ) {}

    public record Turn(
            UUID id,
            String role,
            String content,
            @Nullable Integer tokenCount,
            @Nullable String model,
            @Nullable Integer latencyMs,
            boolean refused,
            Instant createdAt,
            List<SourceDto> sources
    ) {}

    @GetMapping("/{id}")
    @Operation(summary = "Full history for a conversation, oldest turn first",
            description = """
                    Citations are stored as a snapshot taken at answer time, so history stays
                    readable after a document is deleted. Sources whose document has since been
                    removed come back with `available: false`.""")
    public ConversationResponse get(TenantId tenantId, @PathVariable UUID id) {
        var conversation = conversations.find(tenantId, id)
                .orElseThrow(() -> new ConversationNotFoundException(id));

        List<Turn> turns = conversations.findMessages(tenantId, id).stream()
                .map(message -> new Turn(
                        message.id(),
                        message.role().name(),
                        message.content(),
                        message.tokenCount(),
                        message.model(),
                        message.latencyMs(),
                        message.refused(),
                        message.createdAt(),
                        message.sources().stream().map(SourceDto::from).toList()))
                .toList();

        return new ConversationResponse(conversation.id(), conversation.title(),
                conversation.createdAt(), conversation.lastMessageAt(), turns);
    }
}
