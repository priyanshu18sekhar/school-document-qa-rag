package com.docqa.rag.ingestion.extract;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks an extractor for an upload, and is the single place that decides what
 * gets a 415.
 *
 * <p>Resolution is by <em>filename extension first</em>, media type second. That
 * ordering is deliberate. Browsers and curl are unreliable about the
 * {@code Content-Type} on a multipart part: a .docx uploaded from Safari
 * frequently arrives as {@code application/octet-stream}, and a .md file
 * arrives as {@code application/octet-stream} almost everywhere. Trusting the
 * declared type first would reject files we can obviously handle. The extension
 * is under the uploader's control too, but a wrong extension fails loudly
 * during parsing with a clear message rather than silently at the door.
 */
@Component
public class TextExtractorRegistry {

    private final Map<String, TextExtractor> byExtension = new HashMap<>();
    private final Map<String, TextExtractor> byMediaType = new HashMap<>();

    public TextExtractorRegistry(List<TextExtractor> extractors) {
        for (TextExtractor extractor : extractors) {
            extractor.supportedExtensions()
                    .forEach(ext -> byExtension.put(ext.toLowerCase(Locale.ROOT), extractor));
            extractor.supportedMediaTypes()
                    .forEach(type -> byMediaType.put(type.toLowerCase(Locale.ROOT), extractor));
        }
    }

    /**
     * @throws UnsupportedDocumentTypeException mapped to 415 by the API layer
     */
    public TextExtractor resolve(@Nullable String filename, @Nullable String declaredMediaType) {
        String extension = extensionOf(filename);
        if (extension != null) {
            TextExtractor byExt = byExtension.get(extension);
            if (byExt != null) {
                return byExt;
            }
        }

        String mediaType = baseMediaType(declaredMediaType);
        if (mediaType != null) {
            TextExtractor byType = byMediaType.get(mediaType);
            if (byType != null) {
                return byType;
            }
        }

        throw new UnsupportedDocumentTypeException(
                "Unsupported file type%s. Supported types are: %s."
                        .formatted(
                                extension != null ? " '." + extension + "'" : "",
                                String.join(", ", supportedExtensions())));
    }

    public Set<String> supportedExtensions() {
        return new TreeSet<>(byExtension.keySet());
    }

    private static @Nullable String extensionOf(@Nullable String filename) {
        if (filename == null) {
            return null;
        }
        // Defend against a path being sent as the filename ("../../etc/passwd").
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return null;
        }
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static @Nullable String baseMediaType(@Nullable String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.strip().toLowerCase(Locale.ROOT);
    }
}
