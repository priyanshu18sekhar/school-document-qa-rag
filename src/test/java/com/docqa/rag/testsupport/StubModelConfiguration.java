package com.docqa.rag.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the model provider in tests.
 *
 * <p>The real providers are switched off with {@code spring.ai.model.chat=none}
 * and {@code spring.ai.model.embedding=none} (see {@link AbstractPostgresIT}),
 * so these are the only such beans in the context - there is no @Primary
 * shadowing and no chance a test accidentally reaches the network.
 *
 * <p>This is what makes "tests pass with no API key set" true rather than
 * hopeful.
 */
@TestConfiguration
public class StubModelConfiguration {

    /** Must match {@code rag.embedding.dimensions}, or startup validation fails. */
    public static final int DIMENSIONS = 1536;

    @Bean
    public StubEmbeddingModel stubEmbeddingModel() {
        return new StubEmbeddingModel(DIMENSIONS);
    }

    @Bean
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }
}
