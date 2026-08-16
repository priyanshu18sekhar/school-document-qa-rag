package com.docqa.rag.ingestion.extract;

/** Format-specific text extraction. One implementation per supported media type. */
public interface TextExtractor {

    /** Media types this extractor handles, lower-case. */
    java.util.Set<String> supportedMediaTypes();

    /** Filename extensions this extractor handles, lower-case, without the dot. */
    java.util.Set<String> supportedExtensions();

    /**
     * @throws DocumentExtractionException when the bytes are not a readable
     *         document of this type (corrupt, encrypted, truncated)
     */
    ExtractionResult extract(byte[] content, String filename);
}
