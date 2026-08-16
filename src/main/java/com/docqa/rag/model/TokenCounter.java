package com.docqa.rag.model;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real BPE token counting, not {@code text.length() / 4}.
 *
 * <p>Three separate parts of this service need to know how many tokens a piece
 * of text is, and all three break in different ways if the number is a guess:
 *
 * <ul>
 *   <li><b>Chunking.</b> Chunk size is specified in tokens because that is what
 *       the embedding model's context window is measured in. A character-based
 *       estimate is off by 2-3x on text with numbers and rupee symbols - which
 *       is exactly what a fee policy is made of - so "450 tokens" would silently
 *       become 900 and get truncated by the embedding API.</li>
 *   <li><b>History budget.</b> FR-7 asks for a token budget on conversation
 *       history. A budget enforced in characters is not a budget.</li>
 *   <li><b>Cost.</b> The cost metric is only meaningful if the token count is.</li>
 * </ul>
 *
 * <p>The encoding is {@code o200k_base}, which is what the GPT-4.1 and
 * GPT-4o families use. Counts for Claude and Llama differ by a few percent
 * because they use different tokenisers; that is acceptable here because the
 * number is used for budgeting headroom, not for billing. Where it must be
 * exact - the cost metric - we prefer the {@code Usage} the provider returns
 * and only fall back to this estimate when the provider does not report one.
 * See {@link #countOrEstimate}.
 */
@Component
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    public int count(List<String> texts) {
        int total = 0;
        for (String text : texts) {
            total += count(text);
        }
        return total;
    }

    /** Prefer the provider's own count; fall back to the local estimate. */
    public int countOrEstimate(Integer providerReported, String text) {
        return providerReported != null && providerReported > 0 ? providerReported : count(text);
    }

    /**
     * Token ids for {@code text}. The chunker uses these to slice on exact token
     * boundaries when a single sentence is longer than the whole chunk budget
     * and there is no separator left to split on.
     */
    public IntArrayList encode(String text) {
        return encoding.encode(text);
    }

    public String decode(IntArrayList tokens) {
        return encoding.decode(tokens);
    }
}
