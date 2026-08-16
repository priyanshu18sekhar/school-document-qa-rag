package com.docqa.rag.ingestion.chunk;

import com.docqa.rag.ingestion.extract.TextSegment;

import java.util.List;

/**
 * Turns extracted segments into retrieval units.
 *
 * <p>An interface with exactly one implementation, which is usually a smell.
 * It earns its place here because chunking strategy is the single knob with the
 * largest effect on answer quality, and swapping it (for semantic chunking
 * driven by embedding distance, say) is the most likely future change to this
 * codebase. Keeping the seam means that change touches one file plus config.
 */
public interface Chunker {

    /**
     * @param segments      ordered output of a {@link com.docqa.rag.ingestion.extract.TextExtractor}
     * @param documentTitle used as contextual prefix in the embedded text
     * @return chunks with {@code index} assigned sequentially from 0
     */
    List<TextChunk> chunk(List<TextSegment> segments, String documentTitle);
}
