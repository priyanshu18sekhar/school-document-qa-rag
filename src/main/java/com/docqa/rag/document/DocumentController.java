package com.docqa.rag.document;

import com.docqa.rag.document.dto.DocumentResponse;
import com.docqa.rag.document.dto.PagedResponse;
import com.docqa.rag.document.dto.UploadAcceptedResponse;
import com.docqa.rag.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

/**
 * Document management (FR-1, FR-3).
 *
 * <p>The {@link TenantId} parameter is bound by
 * {@link com.docqa.rag.tenant.TenantIdArgumentResolver} from the
 * {@code X-Tenant-Id} header, and a request without it never reaches the method
 * body.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Validated
@Tag(name = "Documents", description = "Upload, inspect and delete source documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document for ingestion",
            description = """
                    Accepts PDF, DOCX, TXT and Markdown up to 20 MB. Returns 202 immediately;
                    ingestion (extract, chunk, embed, persist) runs on a background worker.
                    Re-uploading identical bytes returns the existing document id and does not
                    create duplicate chunks.""")
    public ResponseEntity<UploadAcceptedResponse> upload(
            TenantId tenantId,
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Defaults to the filename")
            @RequestPart(value = "title", required = false) @Nullable String title,
            @Parameter(description = "e.g. FEES, HR, EXAM, TRANSPORT. Upper-cased on write.")
            @RequestPart(value = "category", required = false) @Nullable String category) {

        UploadAcceptedResponse response = documents.upload(tenantId, file, title, category);

        // A duplicate did not create anything, so 200 is more truthful than 202.
        // A fresh upload gets 202 plus a Location header pointing at the
        // resource the caller should poll.
        if (response.duplicate() && response.status() != DocumentStatus.PROCESSING) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/documents/" + response.documentId()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List documents for the tenant, newest first")
    public PagedResponse<DocumentResponse> list(
            TenantId tenantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Nullable DocumentStatus status,
            @RequestParam(required = false) @Nullable String category) {
        return documents.list(tenantId, status, category, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one document, including its ingestion status and error reason")
    public DocumentResponse get(TenantId tenantId, @PathVariable UUID id) {
        return documents.get(tenantId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document and every chunk and embedding derived from it",
            description = "Answers stop citing the document as soon as this returns.")
    public void delete(TenantId tenantId, @PathVariable UUID id) {
        documents.delete(tenantId, id);
    }
}
