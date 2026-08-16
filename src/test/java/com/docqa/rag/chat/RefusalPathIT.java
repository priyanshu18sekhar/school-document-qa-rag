package com.docqa.rag.chat;

import com.docqa.rag.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-6: when nothing clears the similarity threshold, return a fixed refusal
 * <b>without calling the LLM at all</b>.
 *
 * <p>The load-bearing assertion in this class is
 * {@code chatModel.callCount() == 0}. Asserting only on the response text would
 * pass for a system that calls the model, gets a hallucinated answer, and then
 * throws it away - which is not what the requirement says and not what protects
 * a parent from a wrong fee figure. Counting the calls is the only way to prove
 * the gate is in front of the model rather than behind it.
 */
class RefusalPathIT extends AbstractPostgresIT {

    private static final String TENANT = "springfield-elementary";

    private static final String FEE_POLICY = """
            The late fee for term 2 is 500 rupees per week after the due date.
            """;

    @Test
    @DisplayName("an out-of-scope question refuses and never reaches the model")
    void outOfScopeQuestionRefusesWithoutCallingTheModel() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();   // ingestion does not call chat, but be explicit

        ResponseEntity<Map> response =
                ask(TENANT, "Which team won the football world cup in 2022?", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("refused")).isEqualTo(true);
        assertThat(String.valueOf(response.getBody().get("answer")))
                .contains("could not find that in the available documents");
        assertThat((List<?>) response.getBody().get("sources")).isEmpty();

        assertThat(chatModel.callCount())
                .as("the refusal must happen before any model call, not after")
                .isZero();
    }

    @Test
    @DisplayName("a grounded question does reach the model and comes back with citations")
    void groundedQuestionIsAnswered() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();

        ResponseEntity<Map> response = ask(TENANT, "What is the late fee for term 2?", null);

        assertThat(response.getBody().get("refused")).isEqualTo(false);
        assertThat(chatModel.callCount()).isEqualTo(1);

        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) response.getBody().get("sources");
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst())
                .containsEntry("rank", 1)
                .containsEntry("documentTitle", "fee-policy.txt");
        assertThat(String.valueOf(sources.getFirst().get("snippet"))).contains("500 rupees");
        assertThat((Double) sources.getFirst().get("similarity"))
                .isGreaterThanOrEqualTo(0.62);
    }

    @Test
    @DisplayName("the refusal is recorded as refused=true so the rate is measurable")
    void refusalIsPersisted() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ask(TENANT, "Which team won the football world cup in 2022?", null);

        Integer refusedCount = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE role = 'ASSISTANT' AND refused = true",
                Integer.class);
        assertThat(refusedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused turn is excluded from the history sent on the next question")
    void refusedTurnsAreNotReplayedAsHistory() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> first = ask(TENANT, "Who won the football world cup?", null);
        UUID conversationId = UUID.fromString(
                String.valueOf(first.getBody().get("conversationId")));
        chatModel.reset();

        ask(TENANT, "What is the late fee for term 2?", null, conversationId);

        assertThat(chatModel.callCount()).isEqualTo(1);
        assertThat(chatModel.lastPromptText())
                .as("a previous refusal carries no usable information and biases the model "
                        + "toward refusing again")
                .doesNotContain("football");
    }

    @Test
    @DisplayName("when the model reports insufficient context, that becomes a refusal too")
    void modelSentinelBecomesARefusal() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();
        // The chunk cleared the threshold, but the model judges it does not
        // actually answer the question. Second gate.
        chatModel.respondWith("NOT_FOUND_IN_DOCUMENTS");

        ResponseEntity<Map> response = ask(TENANT, "What is the late fee for term 2?", null);

        assertThat(chatModel.callCount()).isEqualTo(1);
        assertThat(response.getBody().get("refused")).isEqualTo(true);
        assertThat(String.valueOf(response.getBody().get("answer")))
                .doesNotContain("NOT_FOUND_IN_DOCUMENTS");
        assertThat((List<?>) response.getBody().get("sources"))
                .as("a refusal must cite nothing - citations imply the answer came from them")
                .isEmpty();
    }

    @Test
    @DisplayName("the response reports the threshold and the best score, so refusals are explainable")
    void refusalExposesTheNearMiss() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> response =
                ask(TENANT, "Which team won the football world cup in 2022?", null);

        Map<String, Object> metadata = (Map<String, Object>) response.getBody().get("metadata");
        assertThat((Double) metadata.get("threshold")).isEqualTo(0.62);
        assertThat((Double) metadata.get("topSimilarity"))
                .as("the best score must be below the threshold - that is why it refused")
                .isLessThan(0.62);
        assertThat(metadata.get("modelMs")).as("no model call, so no model latency").isNull();
    }

    @Test
    @DisplayName("a category filter that matches nothing refuses rather than falling back")
    void categoryFilterThatMatchesNothingRefuses() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();

        // The answer exists, but not in the category asked for. Silently
        // ignoring the filter would be the convenient behaviour and the wrong
        // one: the caller narrowed the search on purpose.
        ResponseEntity<Map> response =
                ask(TENANT, "What is the late fee for term 2?", "TRANSPORT");

        assertThat(response.getBody().get("refused")).isEqualTo(true);
        assertThat(chatModel.callCount()).isZero();
    }
}
