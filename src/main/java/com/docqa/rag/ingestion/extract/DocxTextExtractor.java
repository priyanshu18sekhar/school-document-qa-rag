package com.docqa.rag.ingestion.extract;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DOCX extraction via POI's XWPF model.
 *
 * <p>Two decisions worth defending.
 *
 * <p><b>No page numbers.</b> A .docx does not store pagination - Word lays the
 * document out at render time using the current printer metrics and font
 * substitutions, so the same file paginates differently on two machines. The
 * only way to obtain a page number would be to render the document (LibreOffice
 * headless, or convert to PDF first). We report {@code null} instead. See
 * {@link TextSegment} for why inventing one would be worse than admitting it.
 *
 * <p><b>Tables are flattened row-wise with the header repeated.</b> Naively
 * reading a table cell-by-cell gives you "Term 2" and "5,200" as two unrelated
 * fragments; whichever one retrieval finds is useless on its own. Emitting each
 * row as {@code "Class: 9 | Term: 2 | Amount: 5,200"} keeps the label attached
 * to the number inside a single chunk, which is what makes a fee table
 * answerable at all. Fee schedules and transport routes - the documents this
 * service exists for - are almost entirely tables.
 */
@Component
public class DocxTextExtractor implements TextExtractor {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of(DOCX_MEDIA_TYPE);
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("docx");
    }

    @Override
    public ExtractionResult extract(byte[] content, String filename) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {

            List<TextSegment> segments = new ArrayList<>();
            String currentHeading = null;

            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = PdfTextExtractor.normalise(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    if (isHeading(paragraph)) {
                        // Headings become context for the paragraphs beneath
                        // them rather than standalone chunks. "Late Payment"
                        // on its own retrieves nothing useful; "Late Payment"
                        // prefixed to the clause that follows retrieves well.
                        currentHeading = text;
                        continue;
                    }
                    segments.add(new TextSegment(text, null, currentHeading));

                } else if (element instanceof XWPFTable table) {
                    for (String row : flattenTable(table)) {
                        segments.add(new TextSegment(row, null, currentHeading));
                    }
                }
            }

            if (segments.isEmpty()) {
                throw new DocumentExtractionException(
                        "The Word document contains no extractable text.");
            }
            return new ExtractionResult(segments, null);

        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    "The file could not be read as a Word (.docx) document. Note that the "
                            + "legacy .doc format is not supported.", e);
        }
    }

    private static boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        return style != null && style.toLowerCase().startsWith("heading");
    }

    /**
     * One line per row, cells joined with " | ", header row repeated on every
     * body row.
     *
     * <p>The header repetition is the point. Read cell by cell, a fee table
     * gives you "Class 9" and "5,200" as two unrelated fragments and neither is
     * useful on its own. "Class | Term | Amount — Class 9 | 2 | 5,200" keeps the
     * label attached to the number inside one chunk.
     */
    private static List<String> flattenTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return List.of();
        }
        String header = rowText(rows.getFirst());
        List<String> lines = new ArrayList<>(rows.size());

        for (int r = 1; r < rows.size(); r++) {
            String body = rowText(rows.get(r));
            if (!body.isBlank()) {
                lines.add(header.isBlank() ? body : header + " — " + body);
            }
        }
        // A single-row table is just its own content.
        if (lines.isEmpty() && !header.isBlank()) {
            lines.add(header);
        }
        return lines;
    }

    private static String rowText(XWPFTableRow row) {
        List<String> cells = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            String text = PdfTextExtractor.normalise(cell.getText());
            if (!text.isBlank()) {
                cells.add(text);
            }
        }
        return String.join(" | ", cells);
    }
}
