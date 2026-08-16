package com.docqa.rag.document.dto;

import com.docqa.rag.document.DocumentStatus;

import java.util.UUID;

/**
 * Body of the 202 returned by {@code POST /api/v1/documents}.
 *
 * @param duplicate true when these exact bytes were already uploaded by this
 *                  tenant. The upload still succeeds and still returns the
 *                  document id - it simply did not create a second copy. Saying
 *                  so explicitly is better than a silent no-op, which looks
 *                  identical to a successful upload and leaves the caller
 *                  wondering why the chunk count did not double.
 */
public record UploadAcceptedResponse(
        UUID documentId,
        DocumentStatus status,
        boolean duplicate,
        String message
) {}
