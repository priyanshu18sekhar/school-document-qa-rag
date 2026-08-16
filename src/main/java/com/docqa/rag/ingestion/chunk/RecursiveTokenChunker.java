package com.docqa.rag.ingestion.chunk;

import com.docqa.rag.config.RagProperties;
import com.docqa.rag.ingestion.extract.TextSegment;
import com.docqa.rag.model.TokenCounter;
import com.knuddels.jtokkit.api.IntArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Page-aware recursive chunking with token-measured size and overlap.
 *
 * <h2>The strategy, and why this one</h2>
 *
 * <p><b>1. Chunks never cross a page or section boundary.</b> This is the first
 * rule and everything else bends around it. A chunk that spans pages 3 and 4
 * cannot be cited honestly - you either claim page 3 and are wrong half the
 * time, or you claim "pages 3-4" and make the administrator read both. Since
 * the entire point of this service is a citation an admin can check in ten
 * seconds, exact provenance wins over the marginal recall gain from letting
 * chunks straddle boundaries. The cost is real and is written up as a known
 * limitation: a policy clause split across a page break is weaker in retrieval
 * than it would be otherwise.
 *
 * <p><b>2. Split on the largest semantic boundary that fits.</b> The separator
 * hierarchy is paragraph, then line, then sentence, then clause, then word,
 * then raw tokens. At each level we only descend if a piece is still too big.
 * This is the recursive-character-splitter idea, but measured in BPE tokens
 * instead of characters, because characters are a bad proxy: Indian fee
 * documents are dense in digits, currency symbols and abbreviations, which
 * tokenise at roughly 2 characters per token rather than the usual 4. A
 * character-based "1800 chars ~ 450 tokens" assumption would produce chunks of
 * nearly 900 tokens on exactly the documents this service targets.
 *
 * <p><b>3. 450 tokens with 80 tokens (~18%) of overlap.</b> These numbers come
 * from the shape of the source material rather than from a blog post. School
 * policy documents are written as short numbered clauses, each one or two
 * sentences, grouped under a heading - 450 tokens is enough to hold a heading
 * plus two or three complete clauses, so a question about "the late fee for
 * term 2" lands on a chunk that contains both the qualifier and the number.
 * Smaller chunks (100-200 tokens) score higher on raw similarity but routinely
 * return the amount without the condition attached to it, which is how a wrong
 * figure reaches a parent. Larger chunks (1000+) dilute the embedding: the
 * vector drifts toward the average topic of the page, near-miss questions start
 * clearing the similarity threshold, and the refusal path stops firing when it
 * should. 80 tokens of overlap is about one full clause, so a definition that
 * lands on a boundary survives whole in at least one of the two neighbours.
 *
 * <p><b>4. Undersized tails are merged, not emitted.</b> A 9-token trailing
 * fragment ("Annexure B applies.") embeds to something close to noise and, being
 * short, can score deceptively high on unrelated queries. It is folded back
 * into its predecessor.
 *
 * <h2>Forward progress</h2>
 *
 * <p>Overlap plus a hard size cap is where this kind of splitter usually hangs
 * or duplicates output forever. Two invariants prevent it: every piece leaving
 * {@link #atomise} is guaranteed to fit in a chunk (the final fallback slices on
 * raw token boundaries, which always terminates), and a chunk that contains
 * only carried-over overlap will discard that overlap rather than emit a
 * duplicate. {@code overlapTokens < maxTokens} is validated at startup.
 */
@Component
public class RecursiveTokenChunker implements Chunker {

    /**
     * Ordered coarsest-first. The empty string is not in the list: when every
     * separator has been tried we fall through to a token-boundary slice, which
     * is the only split that is guaranteed to make a piece small enough.
     */
    private static final List<String> SEPARATORS = List.of(
            "\n\n",   // paragraph
            "\n",     // line - matters for clause lists and flattened table rows
            ". ",     // sentence
            "; ",     // clause
            ", ",     // sub-clause
            " "       // word
    );

    private final RagProperties.Chunking config;
    private final TokenCounter tokens;

    public RecursiveTokenChunker(RagProperties properties, TokenCounter tokens) {
        this.config = properties.chunking();
        this.tokens = tokens;
    }

    @Override
    public List<TextChunk> chunk(List<TextSegment> segments, String documentTitle) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }

        List<TextChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        // Rule 1: group only segments that share the same page and heading.
        for (List<TextSegment> group : groupByLocation(segments)) {
            TextSegment first = group.getFirst();
            String joined = group.stream()
                    .map(TextSegment::text)
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElseThrow();

            for (String body : splitToChunkTexts(joined)) {
                result.add(new TextChunk(
                        chunkIndex++,
                        body,
                        buildEmbeddingText(documentTitle, first.heading(), body),
                        first.pageNumber(),
                        first.heading(),
                        tokens.count(body)));
            }
        }
        return result;
    }

    /** Adjacent segments with the same (page, heading) may share a chunk. */
    private static List<List<TextSegment>> groupByLocation(List<TextSegment> segments) {
        List<List<TextSegment>> groups = new ArrayList<>();
        List<TextSegment> current = new ArrayList<>();

        for (TextSegment segment : segments) {
            if (current.isEmpty() || current.getLast().sharesLocationWith(segment)) {
                current.add(segment);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(segment);
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** Steps 2-4: atomise, pack with overlap, merge undersized tail. */
    List<String> splitToChunkTexts(String text) {
        String normalised = text.strip();
        if (normalised.isEmpty()) {
            return List.of();
        }
        if (tokens.count(normalised) <= config.maxTokens()) {
            return List.of(normalised);
        }
        List<Piece> pieces = atomise(normalised, 0);
        List<String> packed = pack(pieces);
        return mergeUndersizedTail(packed);
    }

    private record Piece(String text, int tokenCount) {}

    /**
     * Recursively break {@code text} into pieces that each fit inside one chunk,
     * descending the separator hierarchy only as far as necessary.
     */
    private List<Piece> atomise(String text, int separatorLevel) {
        int count = tokens.count(text);
        if (count <= config.maxTokens()) {
            return text.isBlank() ? List.of() : List.of(new Piece(text, count));
        }

        if (separatorLevel >= SEPARATORS.size()) {
            // No separators left. A single unbroken run longer than the chunk
            // budget - a URL, a table row with no spaces, a language we have no
            // separator for. Slice on token boundaries so the piece is at least
            // decodable and correctly sized.
            return sliceOnTokenBoundaries(text);
        }

        String separator = SEPARATORS.get(separatorLevel);
        String[] parts = text.split(java.util.regex.Pattern.quote(separator), -1);
        if (parts.length <= 1) {
            // This separator does not occur; try the next one without paying
            // for a re-tokenisation.
            return atomise(text, separatorLevel + 1);
        }

        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            // Re-attach the separator so reassembled chunks read naturally and
            // sentence-ending punctuation is not silently deleted.
            String part = (i < parts.length - 1) ? parts[i] + separator : parts[i];
            if (part.isBlank()) {
                continue;
            }
            pieces.addAll(atomise(part, separatorLevel + 1));
        }
        return pieces;
    }

    private List<Piece> sliceOnTokenBoundaries(String text) {
        IntArrayList encoded = tokens.encode(text);
        List<Piece> pieces = new ArrayList<>();
        int size = encoded.size();
        for (int start = 0; start < size; start += config.maxTokens()) {
            int end = Math.min(start + config.maxTokens(), size);
            IntArrayList slice = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                slice.add(encoded.get(i));
            }
            String decoded = tokens.decode(slice);
            if (!decoded.isBlank()) {
                pieces.add(new Piece(decoded, end - start));
            }
        }
        return pieces;
    }

    /**
     * Greedily fill chunks up to {@code maxTokens}, then seed the next chunk
     * with the trailing pieces of the one just emitted, up to
     * {@code overlapTokens}.
     */
    private List<String> pack(List<Piece> pieces) {
        List<String> chunks = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        int currentTokens = 0;
        int seededTokens = 0;   // how much of `current` is carried-over overlap

        for (Piece piece : pieces) {
            boolean fits = currentTokens + piece.tokenCount() <= config.maxTokens();

            if (!fits && currentTokens > seededTokens) {
                // Chunk has genuinely new content: emit and carry overlap.
                chunks.add(join(current));
                current = takeOverlap(current);
                currentTokens = sumTokens(current);
                seededTokens = currentTokens;
                fits = currentTokens + piece.tokenCount() <= config.maxTokens();
            }

            if (!fits) {
                // Still does not fit, which means `current` is nothing but
                // carried-over overlap and the incoming piece needs the whole
                // budget. Drop the overlap rather than emit it as a duplicate
                // chunk - this is the invariant that guarantees progress.
                current.clear();
                currentTokens = 0;
                seededTokens = 0;
            }

            current.add(piece);
            currentTokens += piece.tokenCount();
        }

        if (currentTokens > seededTokens) {
            chunks.add(join(current));
        }
        return chunks;
    }

    private List<Piece> takeOverlap(List<Piece> emitted) {
        List<Piece> overlap = new ArrayList<>();
        int total = 0;
        // Never carry more than half a chunk, regardless of configuration:
        // beyond that the duplication stops being context and starts being
        // near-duplicate chunks competing with each other in the ranking.
        int budget = Math.min(config.overlapTokens(), config.maxTokens() / 2);

        for (int i = emitted.size() - 1; i >= 0; i--) {
            Piece piece = emitted.get(i);
            if (total + piece.tokenCount() > budget) {
                break;
            }
            overlap.addFirst(piece);
            total += piece.tokenCount();
        }
        return overlap;
    }

    /**
     * Fold a too-small final chunk back into its predecessor. Only the tail can
     * be undersized - every other chunk was emitted because it was full.
     */
    private List<String> mergeUndersizedTail(List<String> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        String last = chunks.getLast();
        if (tokens.count(last) >= config.minTokens()) {
            return chunks;
        }
        List<String> merged = new ArrayList<>(chunks.subList(0, chunks.size() - 1));
        String previous = merged.removeLast();
        // The overlap means `last` may already be a suffix of `previous`.
        String combined = previous.endsWith(last) ? previous : previous + " " + last;
        merged.add(combined);
        return merged;
    }

    private static String join(List<Piece> pieces) {
        StringBuilder sb = new StringBuilder();
        for (Piece piece : pieces) {
            sb.append(piece.text());
        }
        return sb.toString().strip();
    }

    private static int sumTokens(List<Piece> pieces) {
        int total = 0;
        for (Piece piece : pieces) {
            total += piece.tokenCount();
        }
        return total;
    }

    /**
     * The contextual prefix. See {@link TextChunk} for why this is separate from
     * the stored content.
     */
    static String buildEmbeddingText(String documentTitle,
                                     @Nullable String heading,
                                     String content) {
        StringBuilder prefix = new StringBuilder();
        if (documentTitle != null && !documentTitle.isBlank()) {
            prefix.append(documentTitle.strip());
        }
        if (heading != null && !heading.isBlank()
                && !Objects.equals(heading.strip(), documentTitle)) {
            if (!prefix.isEmpty()) {
                prefix.append(" > ");
            }
            prefix.append(heading.strip());
        }
        return prefix.isEmpty() ? content : prefix + "\n\n" + content;
    }
}
