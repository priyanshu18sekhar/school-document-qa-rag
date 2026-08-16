package com.docqa.rag.persistence;

/**
 * Conversion to pgvector's text input format, {@code [0.1,0.2,...]}.
 *
 * <p>Shared by the write path ({@code ChunkWriteRepository}) and the read path
 * ({@code VectorSearchRepository}) so the two can never disagree about how a
 * vector is spelled - a formatting mismatch between insert and query would be
 * invisible until similarity scores came back subtly wrong.
 *
 * <p>Formatting by hand rather than registering a JDBC type: the value is
 * always bound as a parameter and cast with {@code CAST(? AS vector)}, never
 * concatenated into SQL, so there is no injection surface, and the floats are
 * always machine-generated. {@link Float#toString} is documented to produce the
 * shortest decimal that round-trips to the same float, so nothing is lost.
 */
public final class PgVector {

    private PgVector() {
    }

    public static String toLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
