package com.docqa.rag.document;

import java.util.UUID;

/**
 * Mapped to 404.
 *
 * <p>Note what this exception does <em>not</em> distinguish: "no such document"
 * and "that document belongs to another tenant" both raise this, with the same
 * message. Returning 403 for the second case would confirm the existence of
 * another tenant's document id, which is a small but real cross-tenant
 * information leak.
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document %s was not found.".formatted(id));
    }
}
