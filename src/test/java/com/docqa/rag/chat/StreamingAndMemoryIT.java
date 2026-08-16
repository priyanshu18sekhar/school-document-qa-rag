package com.docqa.rag.chat;

import com.docqa.rag.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR-5 (streaming with cancellation) and FR-7 (conversation memory).
 */
class StreamingAndMemoryIT extends AbstractPostgresIT {

    private static final String TENANT = "springfield-elementary";

    private static final String FEE_POLICY = """
            The late fee for term 2 is 500 rupees per week after the due date.
            """;

    private WebClient sseClient() {
        return WebClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private Flux<ServerSentEvent<String>> openStream(String question) {
        return sseClient().post()
                .uri("/api/v1/chat/stream")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", question))
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<>() { });
    }

    // ---- FR-5 --------------------------------------------------------------

    @Test
    @DisplayName("tokens stream as separate events, then sources arrive as one terminal event")
    void streamsTokensThenSources() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();
        chatModel.respondWith("The late fee is 500 rupees per week [1].");

        List<ServerSentEvent<String>> events = openStream("What is the late fee for term 2?")
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(events).isNotNull();
        List<String> names = events.stream().map(ServerSentEvent::event).toList();

        assertThat(names)
                .as("multiple token events prove this is a real stream, not one buffered write")
                .filteredOn("token"::equals)
                .hasSizeGreaterThan(1);

        assertThat(names.getLast()).isEqualTo("done");
        assertThat(names.indexOf("sources"))
                .as("sources must be a distinct terminal event, after every token")
                .isEqualTo(names.size() - 2);

        String answer = events.stream()
                .filter(event -> "token".equals(event.event()))
                .map(ServerSentEvent::data)
                .reduce("", (a, b) -> a + b);
        assertThat(answer).contains("500 rupees");

        String sources = events.stream()
                .filter(event -> "sources".equals(event.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow();
        assertThat(sources).contains("fee-policy.txt").contains("\"rank\":1");
    }

    @Test
    @DisplayName("a client disconnect cancels the upstream model call")
    void clientDisconnectCancelsUpstream() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();
        chatModel.respondWith("One two three four five six seven eight nine ten eleven twelve.");
        // Slow enough that we can hang up while the model is still producing.
        chatModel.streamDelay(Duration.ofMillis(300));

        // take(2) then dispose = the client walked away mid-answer.
        openStream("What is the late fee for term 2?")
                .take(2)
                .blockLast(Duration.ofSeconds(20));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(chatModel.streamCancelled())
                        .as("the upstream model call must be cancelled, not left running")
                        .isTrue());
    }

    @Test
    @DisplayName("a refused question streams the refusal without opening a model stream")
    void refusalStreamsWithoutModelCall() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();

        List<ServerSentEvent<String>> events =
                openStream("Which team won the football world cup in 2022?")
                        .collectList().block(Duration.ofSeconds(20));

        assertThat(chatModel.callCount()).isZero();
        assertThat(events).isNotNull();
        assertThat(events.getLast().event()).isEqualTo("done");
        assertThat(events.getLast().data()).contains("\"refused\":true");
    }

    @Test
    @DisplayName("a refusal sentinel never leaks into the stream as visible text")
    void sentinelIsNotStreamedToTheClient() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();
        chatModel.respondWith("NOT_FOUND_IN_DOCUMENTS");

        List<ServerSentEvent<String>> events = openStream("What is the late fee for term 2?")
                .collectList().block(Duration.ofSeconds(20));

        String streamed = events.stream()
                .filter(event -> "token".equals(event.event()))
                .map(ServerSentEvent::data)
                .reduce("", (a, b) -> a + b);

        assertThat(streamed)
                .as("the guard buffer exists so this token never reaches a browser")
                .doesNotContain("NOT_FOUND_IN_DOCUMENTS");
        assertThat(streamed).contains("could not find that in the available documents");
        assertThat(events.getLast().data()).contains("\"refused\":true");
    }

    // ---- FR-7 --------------------------------------------------------------

    @Test
    @DisplayName("a follow-up in the same conversation carries the earlier turns as history")
    void followUpSeesHistory() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();

        ResponseEntity<Map> first = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(first.getBody().get("conversationId")));

        ask(TENANT, "What is the late fee for term 2 for boarders?", null, conversationId);

        assertThat(chatModel.lastPromptText())
                .as("the earlier exchange must be in the prompt for a follow-up to work")
                .contains("What is the late fee for term 2?");
    }

    @Test
    @DisplayName("history is capped by token budget, not only by turn count")
    void historyRespectsTokenBudget() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();
        // A single verbose answer that alone exceeds the 1200-token budget.
        chatModel.respondWith(("The late fee is 500 rupees per week. ").repeat(400));

        ResponseEntity<Map> first = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(first.getBody().get("conversationId")));

        ask(TENANT, "What is the late fee for term 2 for boarders?", null, conversationId);

        String prompt = chatModel.lastPromptText();
        assertThat(prompt)
                .as("an oversized previous answer must be dropped, or it evicts the retrieved "
                        + "context that actually grounds the answer")
                .doesNotContain("The late fee is 500 rupees per week. The late fee is 500");
        assertThat(prompt).contains("CONTEXT");
    }

    @Test
    @DisplayName("GET /conversations/{id} returns the full history with citations")
    void conversationHistoryIsReadable() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> answer = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(answer.getBody().get("conversationId")));

        ResponseEntity<Map> history = get("/api/v1/conversations/" + conversationId, TENANT);
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) history.getBody().get("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "USER");
        assertThat(messages.get(1)).containsEntry("role", "ASSISTANT");
        assertThat((List<?>) messages.get(1).get("sources")).hasSize(1);
        // The first question becomes the conversation title, so a list of
        // conversations is readable without an extra model call.
        assertThat(history.getBody().get("title")).isEqualTo("What is the late fee for term 2?");
    }

    @Test
    @DisplayName("a conversation belonging to another tenant is a 404")
    void conversationsAreTenantScoped() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        ResponseEntity<Map> answer = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(answer.getBody().get("conversationId")));

        assertThat(get("/api/v1/conversations/" + conversationId, "shelbyville-high")
                .getStatusCode().value())
                .isEqualTo(404);
    }
}
