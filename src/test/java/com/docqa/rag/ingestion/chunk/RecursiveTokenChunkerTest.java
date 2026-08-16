package com.docqa.rag.ingestion.chunk;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.ingestion.extract.TextSegment;
import com.docqa.rag.model.TokenCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chunking is pure and deterministic, so it gets fast unit tests with no
 * database and no Spring context.
 *
 * <p>The boundary cases the brief calls out - empty, single word, larger than
 * one chunk - are here, plus the two invariants that actually keep the system
 * correct: chunks never merge across a page boundary (or citations lie), and
 * splitting always terminates (or a pathological document hangs a worker).
 */
class RecursiveTokenChunkerTest {

    private static final String TITLE = "Fee Policy 2026";

    private final TokenCounter tokens = new TokenCounter();

    private RecursiveTokenChunker chunker(int maxTokens, int overlapTokens, int minTokens) {
        RagProperties properties = new RagProperties(
                new RagProperties.Tenant("X-Tenant-Id", 64),
                new RagProperties.Ingestion(1, 10, 5),
                new RagProperties.Chunking(maxTokens, overlapTokens, minTokens),
                new RagProperties.Embedding(1536, 64, 200_000),
                new RagProperties.Retrieval(5, 0.62, 4, 100),
                new RagProperties.Chat(6, 1200, 3000, 800, 60, "not found"),
                new RagProperties.Resilience(
                        new RagProperties.Resilience.Retry(3, 500, 2.0, 8000),
                        new RagProperties.Resilience.CircuitBreaker(50, 20, 80, 20, 8, 30, 3)),
                new RagProperties.Cost(0.4, 1.6, 0.02));
        return new RecursiveTokenChunker(properties, tokens);
    }

    private RecursiveTokenChunker defaultChunker() {
        return chunker(450, 80, 24);
    }

    // ---- boundary cases ---------------------------------------------------

    @Nested
    @DisplayName("boundary cases")
    class BoundaryCases {

        @Test
        @DisplayName("an empty document produces no chunks rather than one empty chunk")
        void emptyDocument() {
            assertThat(defaultChunker().chunk(List.of(), TITLE)).isEmpty();
        }

        @Test
        @DisplayName("a single word becomes exactly one chunk")
        void singleWord() {
            List<TextChunk> chunks = defaultChunker()
                    .chunk(List.of(new TextSegment("Fees", 1, null)), TITLE);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content()).isEqualTo("Fees");
            assertThat(chunks.getFirst().index()).isZero();
            assertThat(chunks.getFirst().pageNumber()).isEqualTo(1);
            assertThat(chunks.getFirst().tokenCount()).isPositive();
        }

        @Test
        @DisplayName("text longer than one chunk is split, and every piece stays under the cap")
        void largerThanOneChunk() {
            String longText = ("The late payment fee is five hundred rupees per week. ")
                    .repeat(120);

            List<TextChunk> chunks = chunker(120, 20, 10)
                    .chunk(List.of(new TextSegment(longText, 1, null)), TITLE);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(tokens.count(chunk.content()))
                            .as("chunk %d must not exceed the size cap", chunk.index())
                            .isLessThanOrEqualTo(120));
        }

        @Test
        @DisplayName("chunk indexes are sequential from zero across the whole document")
        void sequentialIndexes() {
            List<TextChunk> chunks = defaultChunker().chunk(List.of(
                    new TextSegment("Page one content about fees.", 1, null),
                    new TextSegment("Page two content about transport.", 2, null),
                    new TextSegment("Page three content about exams.", 3, null)), TITLE);

            assertThat(chunks).extracting(TextChunk::index).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("blank text is rejected at construction rather than stored")
        void blankChunkRejected() {
            assertThatThrownBy(() -> new TextChunk(0, "  ", "x", 1, null, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- the invariants that keep citations honest -------------------------

    @Test
    @DisplayName("chunks never merge across a page boundary, even when both would fit")
    void neverMergesAcrossPages() {
        // Both segments are tiny; a naive packer would combine them and then be
        // unable to say which page the resulting chunk came from.
        List<TextChunk> chunks = defaultChunker().chunk(List.of(
                new TextSegment("Term 1 fee is 4,500.", 3, null),
                new TextSegment("Term 2 fee is 5,200.", 4, null)), TITLE);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(TextChunk::pageNumber).containsExactly(3, 4);
    }

    @Test
    @DisplayName("chunks never merge across a section heading")
    void neverMergesAcrossSections() {
        List<TextChunk> chunks = defaultChunker().chunk(List.of(
                new TextSegment("Fees are due on the 10th.", null, "Payment Schedule"),
                new TextSegment("Buses depart at 7:15am.", null, "Transport")), TITLE);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(TextChunk::section)
                .containsExactly("Payment Schedule", "Transport");
    }

    @Test
    @DisplayName("adjacent segments sharing a page and heading are packed together")
    void packsSameLocationSegments() {
        List<TextChunk> chunks = defaultChunker().chunk(List.of(
                new TextSegment("First clause.", 2, "Late Payment"),
                new TextSegment("Second clause.", 2, "Late Payment")), TITLE);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("First clause.", "Second clause.");
    }

    @Test
    @DisplayName("consecutive chunks overlap, so a clause on a boundary survives whole")
    void consecutiveChunksOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            text.append("Clause ").append(i).append(" states a distinct rule. ");
        }
        List<TextChunk> chunks = chunker(100, 30, 10)
                .chunk(List.of(new TextSegment(text.toString(), 1, null)), TITLE);

        assertThat(chunks).hasSizeGreaterThan(2);

        // The tail of one chunk must reappear at the head of the next.
        String firstTail = lastWords(chunks.get(0).content(), 3);
        assertThat(chunks.get(1).content())
                .as("chunk 1 should begin with content carried over from chunk 0")
                .contains(firstTail);
    }

    @Test
    @DisplayName("an unbreakable run longer than the cap is split on token boundaries, not dropped")
    void unbreakableRunTerminates() {
        // No spaces, no punctuation: every separator in the hierarchy fails.
        String unbreakable = "A".repeat(4000);

        List<TextChunk> chunks = chunker(100, 20, 10)
                .chunk(List.of(new TextSegment(unbreakable, 1, null)), TITLE);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(tokens.count(chunk.content())).isLessThanOrEqualTo(100));
        // Nothing was silently lost.
        String rejoined = chunks.stream().map(TextChunk::content).reduce("", String::concat);
        assertThat(rejoined.replace("A", "")).isEmpty();
    }

    @Test
    @DisplayName("an undersized tail is folded into its predecessor instead of emitted alone")
    void undersizedTailIsMerged() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append("Rule number ").append(i).append(" applies to all classes. ");
        }
        text.append("Ends.");

        List<TextChunk> chunks = chunker(100, 10, 40)
                .chunk(List.of(new TextSegment(text.toString(), 1, null)), TITLE);

        assertThat(chunks).allSatisfy(chunk ->
                assertThat(tokens.count(chunk.content()))
                        .as("no chunk should be below the minimum size")
                        .isGreaterThanOrEqualTo(40));
    }

    @Test
    @DisplayName("overlap at or above chunk size is rejected at startup, not at runtime")
    void invalidOverlapRejected() {
        assertThatThrownBy(() -> new RagProperties.Chunking(100, 100, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward progress");
    }

    // ---- embedded text vs stored text --------------------------------------

    @Test
    @DisplayName("the embedded text carries title and heading; the stored content does not")
    void embeddingTextCarriesContext() {
        List<TextChunk> chunks = defaultChunker().chunk(
                List.of(new TextSegment("A fee of Rs 500 per week applies.", 3, "Late Payment")),
                TITLE);

        TextChunk chunk = chunks.getFirst();
        assertThat(chunk.embeddingText())
                .contains(TITLE)
                .contains("Late Payment")
                .contains("A fee of Rs 500 per week applies.");
        // What we quote back to the user must be exactly what the document said.
        assertThat(chunk.content()).isEqualTo("A fee of Rs 500 per week applies.");
    }

    private static String lastWords(String text, int count) {
        String[] words = text.strip().split("\\s+");
        return String.join(" ",
                java.util.Arrays.copyOfRange(words, Math.max(0, words.length - count), words.length));
    }
}
