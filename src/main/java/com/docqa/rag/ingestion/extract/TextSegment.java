package com.docqa.rag.ingestion.extract;

import org.jspecify.annotations.Nullable;

/**
 * A run of text together with the position metadata we can <em>honestly</em>
 * claim for it.
 *
 * <p>Both position fields are nullable and that is the point. A DOCX file does
 * not contain page numbers - Word computes pagination at render time from the
 * printer metrics, so there is no page number in the file to extract. A plain
 * text file has neither pages nor styled headings. Rather than inventing a
 * plausible-looking page number (which would produce citations that point a
 * school administrator at the wrong page of a printed policy), the extractor
 * reports what the format actually knows and the API returns {@code null}.
 *
 * <p>A citation that says "no page information" is a small annoyance. A citation
 * that says "page 4" when the fee table is on page 7 is the failure this whole
 * assignment is about.
 *
 * @param text        the extracted text, never blank
 * @param pageNumber  1-based page, or {@code null} where the format has no pages
 * @param heading     nearest enclosing section heading, or {@code null}
 */
public record TextSegment(String text, @Nullable Integer pageNumber, @Nullable String heading) {

    public TextSegment {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("segment text must not be blank");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("page number is 1-based, got " + pageNumber);
        }
        heading = (heading == null || heading.isBlank()) ? null : heading.trim();
    }

    public static TextSegment of(String text, @Nullable Integer pageNumber) {
        return new TextSegment(text, pageNumber, null);
    }

    /** True when two segments may be packed into the same chunk. */
    public boolean sharesLocationWith(TextSegment other) {
        return java.util.Objects.equals(pageNumber, other.pageNumber)
                && java.util.Objects.equals(heading, other.heading);
    }
}
