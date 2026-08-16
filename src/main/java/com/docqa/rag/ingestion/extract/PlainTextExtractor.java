package com.docqa.rag.ingestion.extract;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain text and Markdown.
 *
 * <p>Text is split into blank-line-separated blocks. Markdown headings
 * ({@code # ...}) are recognised and attached to the blocks beneath them as
 * section context, the same way DOCX heading styles are - that structure is the
 * only positional metadata these formats have, and dropping it leaves chunks
 * with no context at all.
 *
 * <p>Neither format has pages, so {@code pageNumber} is {@code null}.
 */
@Component
public class PlainTextExtractor implements TextExtractor {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("text/plain", "text/markdown", "text/x-markdown");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("txt", "md", "markdown", "text");
    }

    @Override
    public ExtractionResult extract(byte[] content, String filename) {
        // UTF-8 with replacement rather than strict decoding: a policy document
        // saved from Windows with one stray byte should still ingest, with one
        // mangled character, rather than failing the whole upload.
        String text = new String(content, StandardCharsets.UTF_8);
        text = PdfTextExtractor.normalise(stripBom(text));

        if (text.isBlank()) {
            throw new DocumentExtractionException("The file is empty.");
        }

        List<TextSegment> segments = new ArrayList<>();
        String currentHeading = null;
        StringBuilder block = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(segments, block, currentHeading);
                currentHeading = heading.group(1).strip();
            } else if (line.isBlank()) {
                flush(segments, block, currentHeading);
            } else {
                if (!block.isEmpty()) {
                    block.append('\n');
                }
                block.append(line);
            }
        }
        flush(segments, block, currentHeading);

        if (segments.isEmpty()) {
            throw new DocumentExtractionException("The file contains no extractable text.");
        }
        return new ExtractionResult(segments, null);
    }

    private static void flush(List<TextSegment> segments, StringBuilder block, String heading) {
        String text = block.toString().strip();
        block.setLength(0);
        if (!text.isBlank()) {
            segments.add(new TextSegment(text, null, heading));
        }
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
