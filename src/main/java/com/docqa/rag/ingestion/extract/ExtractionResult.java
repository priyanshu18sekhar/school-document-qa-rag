package com.docqa.rag.ingestion.extract;

import java.util.List;

/**
 * What a {@link TextExtractor} produces.
 *
 * @param segments  ordered text segments; may be empty for a document that
 *                  parses successfully but contains no extractable text
 *                  (a scanned PDF with no text layer, for instance)
 * @param pageCount number of pages, or {@code null} for paginationless formats
 */
public record ExtractionResult(List<TextSegment> segments, Integer pageCount) {

    public ExtractionResult {
        segments = List.copyOf(segments);
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), null);
    }
}
