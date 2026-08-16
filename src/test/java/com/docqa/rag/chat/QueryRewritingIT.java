package com.docqa.rag.chat;

import com.docqa.rag.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-7's follow-up requirement: <em>"so follow-ups like 'what about for class 9?'
 * work"</em>.
 *
 * <p>Putting history in the prompt is necessary but not sufficient, because
 * retrieval runs <b>before</b> the model does. This was a real failure, found by
 * running the service against a live provider rather than by reading the code:
 * "What is the tuition fee for Class 9 in term 2?" answered correctly, and the
 * follow-up "And for Class 11 Science?" was <em>refused</em> - the raw follow-up
 * embedded to 0.7492, below the threshold, so the refusal fired before the model
 * ever saw the history sitting in the prompt. With rewriting the same follow-up
 * embeds at 0.8466 and answers correctly.
 *
 * <p>Rewriting is switched off in {@link AbstractPostgresIT} so that other tests
 * do not depend on a stub model's canned "rewrite". This class turns it on.
 */
@TestPropertySource(properties = "rag.chat.query-rewriting-enabled=true")
class QueryRewritingIT extends AbstractPostgresIT {

    private static final String TENANT = "springfield-elementary";
    private static final String FEE_POLICY = """
            The late fee for term 2 is 500 rupees per week after the due date.
            """;

    @Test
    @DisplayName("a first question has no history, so it costs no rewrite call")
    void firstQuestionIsNotRewritten() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");
        chatModel.reset();

        ask(TENANT, "What is the late fee for term 2?", null);

        assertThat(chatModel.callCount())
                .as("nothing to resolve against, so no extra call")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a follow-up is sent to the model to be made standalone before retrieval")
    void followUpIsRewrittenBeforeRetrieval() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> first = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(first.getBody().get("conversationId")));

        chatModel.reset();
        ask(TENANT, "And for term 3?", null, conversationId);

        assertThat(chatModel.callCount())
                .as("the follow-up must be rewritten before it is embedded")
                .isGreaterThanOrEqualTo(1);

        // The first call of the turn is the rewrite, and it must carry the
        // earlier turn - without it there is nothing to resolve "term 3" against.
        String rewritePrompt = chatModel.prompts().getFirst().getInstructions().stream()
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(rewritePrompt)
                .contains("self-contained search query")
                .contains("What is the late fee for term 2?")
                .contains("And for term 3?");
    }

    @Test
    @DisplayName("a rewrite failure falls back to the original question rather than erroring")
    void rewriteFailureIsNotFatal() {
        uploadAndWait(TENANT, "fee-policy.txt", FEE_POLICY, "FEES");

        ResponseEntity<Map> first = ask(TENANT, "What is the late fee for term 2?", null);
        UUID conversationId =
                UUID.fromString(String.valueOf(first.getBody().get("conversationId")));

        chatModel.reset();
        chatModel.failWith(new IllegalStateException("provider exploded"));

        ResponseEntity<Map> response =
                ask(TENANT, "What is the late fee for term 2?", null, conversationId);

        // The rewrite fails, we fall back to the original question, retrieval
        // still succeeds - and then the *answer* call fails, which is a 503.
        // The point is that the optimisation failing does not produce a
        // different or more confusing error than the outage itself.
        assertThat(response.getStatusCode().value())
                .as("a model outage is a retryable 503, not a 500")
                .isEqualTo(503);
    }
}
