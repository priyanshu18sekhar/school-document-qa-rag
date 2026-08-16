package com.docqa.rag.retrieval;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A chunk that cleared retrieval, with everything needed to cite it.
 *
 * @param similarity cosine similarity in [0, 1], computed by Postgres as
 *                   {@code 1 - (embedding <=> query)}. Never computed in Java.
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String documentTitle,
        @Nullable String documentCategory,
        int chunkIndex,
        @Nullable Integer pageNumber,
        @Nullable String section,
        String content,
        double similarity
) {

    /** Short form for the {@code sources} array; the full text stays in the prompt. */
    public String snippet(int maxChars) {
        String collapsed = content.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        // Cut on a word boundary so the snippet does not end mid-word.
        int cut = collapsed.lastIndexOf(' ', maxChars);
        return collapsed.substring(0, cut > maxChars / 2 ? cut : maxChars).strip() + "…";
    }

    /** Human-readable location used in the prompt and in log lines. */
    public String citationLabel() {
        StringBuilder sb = new StringBuilder(documentTitle);
        if (pageNumber != null) {
            sb.append(", page ").append(pageNumber);
        }
        if (section != null && !section.isBlank()) {
            sb.append(" (").append(section).append(')');
        }
        return sb.toString();
    }
}
