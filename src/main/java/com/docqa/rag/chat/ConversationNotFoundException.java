package com.docqa.rag.chat;

import java.util.UUID;

/** Mapped to 404. Same reasoning as DocumentNotFoundException: never 403. */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID id) {
        super("Conversation %s was not found.".formatted(id));
    }
}
