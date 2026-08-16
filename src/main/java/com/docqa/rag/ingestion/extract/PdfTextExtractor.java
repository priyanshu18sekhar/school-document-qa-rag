package com.docqa.rag.ingestion.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF extraction, one segment per page.
 *
 * <p>The important detail is the loop: rather than calling
 * {@code PDFTextStripper.getText(doc)} once and getting a single string with
 * page breaks lost, we set the start and end page and extract each page
 * separately. That is what makes {@code page_number} on every chunk a fact
 * rather than an estimate, and it is what lets a citation say "fee-policy.pdf,
 * page 7" and be right.
 *
 * <p>{@code setSortByPosition(true)} matters for the kind of documents this
 * service is aimed at. Fee schedules and transport route tables are laid out in
 * columns; without positional sorting PDFBox emits text in content-stream
 * order, which for a two-column table interleaves the columns and produces
 * chunks like "Term 1 Term 2 Rs 4,500 Rs 5,200" where the association between
 * label and amount is destroyed. The model then confidently quotes the wrong
 * figure - the exact failure mode this assignment calls out.
 */
@Component
public class PdfTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("pdf");
    }

    @Override
    public ExtractionResult extract(byte[] content, String filename) {
        try (PDDocument document = Loader.loadPDF(content)) {

            if (document.isEncrypted()) {
                // PDFBox can open some encrypted PDFs with an empty password.
                // If we got here it opened, but a permissions-restricted file
                // may still refuse extraction - fail with a message the admin
                // can act on rather than a stack trace.
                log.debug("PDF opened with encryption present");
            }

            int pageCount = document.getNumberOfPages();
            List<TextSegment> segments = new ArrayList<>(pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setAddMoreFormatting(true);

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalise(stripper.getText(document));
                if (!text.isBlank()) {
                    segments.add(new TextSegment(text, page, null));
                }
            }

            if (segments.isEmpty() && pageCount > 0) {
                // A PDF with pages but no text layer is almost always a scan.
                // Say so explicitly: "0 chunks" with no explanation sends the
                // admin looking for a bug in our code instead of running OCR.
                throw new DocumentExtractionException(
                        "The PDF has %d page(s) but contains no extractable text. It is most "
                                .formatted(pageCount)
                                + "likely a scanned image; OCR is required and is not supported.");
            }

            return new ExtractionResult(segments, pageCount);

        } catch (DocumentExtractionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentExtractionException(
                    "The file could not be read as a PDF. It may be corrupt, truncated or "
                            + "password protected.", e);
        }
    }

    /**
     * PDF text extraction routinely produces soft hyphens, non-breaking spaces
     * and CRLF. Left alone these end up inside embeddings and inside the
     * snippet shown to the user, and the non-breaking space in particular
     * breaks naive whitespace splitting later in the pipeline.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(' ', ' ')   // non-breaking space
                .replace("­", "")    // soft hyphen
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
