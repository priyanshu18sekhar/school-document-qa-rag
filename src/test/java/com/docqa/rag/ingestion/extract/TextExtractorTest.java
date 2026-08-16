package com.docqa.rag.ingestion.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extraction tests.
 *
 * <p>The PDF and DOCX fixtures are generated in-test with PDFBox and POI rather
 * than committed as binaries. That keeps the repository free of opaque blobs
 * nobody can diff, and makes the fixture's content visible right next to the
 * assertion about it - when a test says "the fee table is on page 2", you can
 * see the code that put it there.
 */
class TextExtractorTest {

    // ---- PDF ---------------------------------------------------------------

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private final PdfTextExtractor extractor = new PdfTextExtractor();

        @Test
        @DisplayName("page numbers are preserved, one segment per page")
        void preservesPageNumbers() {
            byte[] pdf = pdfWithPages(
                    "Admission policy overview for the academic year.",
                    "The late fee for term 2 is 500 rupees per week.",
                    "Transport routes and timings are listed in Annexure B.");

            ExtractionResult result = extractor.extract(pdf, "policy.pdf");

            assertThat(result.pageCount()).isEqualTo(3);
            assertThat(result.segments()).hasSize(3);
            assertThat(result.segments()).extracting(TextSegment::pageNumber)
                    .containsExactly(1, 2, 3);

            // The whole reason page numbers matter: the fee figure must be
            // attributable to the page it is actually printed on.
            TextSegment feePage = result.segments().get(1);
            assertThat(feePage.pageNumber()).isEqualTo(2);
            assertThat(feePage.text()).contains("500 rupees");
        }

        @Test
        @DisplayName("a blank page is skipped rather than emitted as an empty segment")
        void skipsBlankPages() {
            byte[] pdf = pdfWithPages("Content on page one.", "", "Content on page three.");

            ExtractionResult result = extractor.extract(pdf, "policy.pdf");

            assertThat(result.pageCount()).isEqualTo(3);
            assertThat(result.segments()).hasSize(2);
            assertThat(result.segments()).extracting(TextSegment::pageNumber)
                    .as("skipping a blank page must not renumber the pages after it")
                    .containsExactly(1, 3);
        }

        @Test
        @DisplayName("a PDF with pages but no text layer says so instead of silently ingesting nothing")
        void scannedPdfGivesAnActionableMessage() {
            byte[] pdf = pdfWithPages("", "");

            assertThatThrownBy(() -> extractor.extract(pdf, "scan.pdf"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining("scanned")
                    .hasMessageContaining("OCR");
        }

        @Test
        @DisplayName("bytes that are not a PDF fail with a clean message, not a parser stack trace")
        void corruptPdfIsRejectedCleanly() {
            assertThatThrownBy(() ->
                    extractor.extract("this is not a pdf".getBytes(StandardCharsets.UTF_8), "x.pdf"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining("could not be read as a PDF");
        }
    }

    // ---- DOCX --------------------------------------------------------------

    @Nested
    @DisplayName("DOCX")
    class Docx {

        private final DocxTextExtractor extractor = new DocxTextExtractor();

        @Test
        @DisplayName("heading styles become section context on the paragraphs beneath them")
        void headingsBecomeSections() throws Exception {
            byte[] docx = docx(document -> {
                var heading = document.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText("Late Payment");

                var body = document.createParagraph();
                body.createRun().setText("A fee of 500 rupees per week applies after the due date.");
            });

            ExtractionResult result = extractor.extract(docx, "fees.docx");

            assertThat(result.segments()).hasSize(1);
            assertThat(result.segments().getFirst().heading()).isEqualTo("Late Payment");
            assertThat(result.segments().getFirst().text()).contains("500 rupees");
        }

        @Test
        @DisplayName("page number is null because a .docx does not contain one")
        void hasNoPageNumbers() throws Exception {
            byte[] docx = docx(document ->
                    document.createParagraph().createRun().setText("Some policy text."));

            ExtractionResult result = extractor.extract(docx, "fees.docx");

            // Word paginates at render time, so any page number here would be
            // invented. A citation with no page beats a citation with a wrong one.
            assertThat(result.pageCount()).isNull();
            assertThat(result.segments()).allSatisfy(segment ->
                    assertThat(segment.pageNumber()).isNull());
        }

        @Test
        @DisplayName("table rows keep their header, so a figure stays attached to its label")
        void tableRowsCarryTheirHeader() throws Exception {
            byte[] docx = docx(document -> {
                var table = document.createTable(3, 2);
                table.getRow(0).getCell(0).setText("Class");
                table.getRow(0).getCell(1).setText("Term 2 fee");
                table.getRow(1).getCell(0).setText("Class 8");
                table.getRow(1).getCell(1).setText("4500");
                table.getRow(2).getCell(0).setText("Class 9");
                table.getRow(2).getCell(1).setText("5200");
            });

            ExtractionResult result = extractor.extract(docx, "fees.docx");
            List<String> texts = result.segments().stream().map(TextSegment::text).toList();

            // "5200" alone retrieves nothing useful and answers nothing safely.
            assertThat(texts).anySatisfy(text ->
                    assertThat(text).contains("Class 9").contains("5200")
                            .contains("Term 2 fee"));
            assertThat(texts).anySatisfy(text ->
                    assertThat(text).contains("Class 8").contains("4500"));
        }

        @Test
        @DisplayName("a legacy .doc is rejected with a message that names the problem")
        void legacyDocIsRejected() {
            assertThatThrownBy(() -> extractor.extract(
                    "not a docx".getBytes(StandardCharsets.UTF_8), "old.doc"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining(".doc format is not supported");
        }
    }

    // ---- plain text and markdown -------------------------------------------

    @Nested
    @DisplayName("text and markdown")
    class PlainText {

        private final PlainTextExtractor extractor = new PlainTextExtractor();

        @Test
        @DisplayName("markdown headings become section context")
        void markdownHeadings() {
            String markdown = """
                    # Fee Policy

                    Fees are due by the 10th of each term.

                    ## Late Payment

                    A fee of 500 rupees per week applies.
                    """;

            ExtractionResult result = extractor.extract(
                    markdown.getBytes(StandardCharsets.UTF_8), "fees.md");

            assertThat(result.segments()).hasSize(2);
            assertThat(result.segments().get(0).heading()).isEqualTo("Fee Policy");
            assertThat(result.segments().get(1).heading()).isEqualTo("Late Payment");
        }

        @Test
        @DisplayName("blank lines separate blocks; whitespace-only files are rejected")
        void blocksAndBlankFiles() {
            ExtractionResult result = extractor.extract(
                    "First block.\n\nSecond block.".getBytes(StandardCharsets.UTF_8), "a.txt");
            assertThat(result.segments()).hasSize(2);

            assertThatThrownBy(() -> extractor.extract(
                    "   \n \n".getBytes(StandardCharsets.UTF_8), "b.txt"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining("empty");
        }
    }

    // ---- registry ----------------------------------------------------------

    @Nested
    @DisplayName("type resolution")
    class Registry {

        private final TextExtractorRegistry registry = new TextExtractorRegistry(List.of(
                new PdfTextExtractor(), new DocxTextExtractor(), new PlainTextExtractor()));

        @Test
        @DisplayName("resolves by extension even when the browser sends octet-stream")
        void extensionWinsOverDeclaredType() {
            // Safari and curl both do this routinely; trusting the declared
            // type first would 415 files we can obviously handle.
            assertThat(registry.resolve("fees.docx", "application/octet-stream"))
                    .isInstanceOf(DocxTextExtractor.class);
            assertThat(registry.resolve("fees.md", "application/octet-stream"))
                    .isInstanceOf(PlainTextExtractor.class);
        }

        @Test
        @DisplayName("falls back to the media type when there is no usable extension")
        void mediaTypeFallback() {
            assertThat(registry.resolve("upload", "application/pdf"))
                    .isInstanceOf(PdfTextExtractor.class);
            assertThat(registry.resolve(null, "text/plain; charset=utf-8"))
                    .isInstanceOf(PlainTextExtractor.class);
        }

        @Test
        @DisplayName("a path passed as a filename cannot escape the extension check")
        void pathsInFilenamesAreHandled() {
            assertThat(registry.resolve("../../etc/passwd.pdf", null))
                    .isInstanceOf(PdfTextExtractor.class);
        }

        @Test
        @DisplayName("anything else is a 415 with the supported list in the message")
        void unsupportedTypesAreRejected() {
            assertThatThrownBy(() -> registry.resolve("budget.xlsx", "application/octet-stream"))
                    .isInstanceOf(UnsupportedDocumentTypeException.class)
                    .hasMessageContaining("xlsx")
                    .hasMessageContaining("pdf");
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private static byte[] pdfWithPages(String... pageTexts) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the PDF fixture", e);
        }
    }

    private interface DocxBuilder {
        void build(XWPFDocument document) throws Exception;
    }

    private static byte[] docx(DocxBuilder builder) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(document);
            document.write(out);
            return out.toByteArray();
        }
    }
}
