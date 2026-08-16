package com.docqa.rag.retrieval;

import com.docqa.rag.testsupport.AbstractPostgresIT;
import com.docqa.rag.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sweeps the similarity threshold over a labelled question set and prints where
 * the system starts answering questions it should refuse.
 *
 * <p>This exists because "0.62 felt about right" is not an answer. It ingests
 * the real sample corpus in {@code samples/}, runs two sets of questions
 * through retrieval - ones the corpus <em>does</em> answer and ones it
 * <em>does not</em> - and reports, for each candidate threshold, how many of
 * each get through. The number to pick is the highest threshold that still
 * answers everything answerable, minus a small margin.
 *
 * <p><b>Read the output with this caveat.</b> Under {@code ./mvnw verify} this
 * runs against the offline stub embedder, which is lexical, not semantic (see
 * {@link com.docqa.rag.testsupport.StubEmbeddingModel}). Its absolute
 * similarity values are <em>not</em> the values a real embedding model
 * produces, so the number printed here calibrates the test double, not
 * production. What transfers is the method and the harness.
 *
 * <p>To calibrate for real, point it at a live provider:
 *
 * <pre>
 * OPENAI_API_KEY=sk-... ./mvnw verify -Dit.test=ThresholdCalibrationIT \
 *     -Dspring.ai.model.embedding=openai
 * </pre>
 *
 * <p>The same information is available in production without this harness: on
 * every refusal, {@link RetrievalService} logs the best-scoring chunk that did
 * not clear the bar, so a week of logs tells you whether the threshold is too
 * high.
 */
class ThresholdCalibrationIT extends AbstractPostgresIT {

    private static final String TENANT = "calibration";

    /** Questions the sample corpus genuinely answers. Retrieval should find these. */
    private static final List<String> ANSWERABLE = List.of(
            "What is the late fee per week for a term fee paid after the due date?",
            "What is the tuition fee for Class 9 in term 2?",
            "What is the sibling concession for the second child?",
            "What is the transport charge per term for a distance of 10 km to 15 km?",
            "What time does the route 4 bus depart in the morning?",
            "How many days of casual leave are staff entitled to per year?",
            "How many weeks of maternity leave are available?",
            "What attendance percentage is required to sit the term examination?",
            "What is the pass mark in each subject?",
            "What is the charge for re-evaluation of a paper per subject?");

    /**
     * Questions the corpus does not answer. Retrieval must find nothing above
     * the threshold, so the system refuses without calling the model.
     *
     * <p>The last three are the interesting ones - near misses. They use the
     * corpus's own vocabulary but ask about something it never states, which is
     * exactly the case where a too-low threshold produces a confident wrong
     * answer instead of a refusal.
     */
    private static final List<String> UNANSWERABLE = List.of(
            "Which team won the football world cup in 2022?",
            "What is the capital city of Australia?",
            "How do I reset my email password?",
            "What is the school's policy on staff medical insurance premiums?",
            "What is the late fee for the hostel accommodation charge?",
            "What is the tuition fee for the pre-nursery playgroup in term 2?");

    private static final double[] CANDIDATES = {
            0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.58, 0.60, 0.62, 0.65, 0.70, 0.75, 0.80};

    @Autowired private VectorSearchRepository search;

    @Test
    @DisplayName("sweep the threshold over a labelled question set and report the operating point")
    void calibrate() throws IOException {
        ingestSampleCorpus();

        System.out.printf("%n=== Threshold calibration (embedder: %s) ===%n",
                embeddingModel.getClass().getSimpleName());
        System.out.printf("%-11s %-22s %-24s %s%n",
                "threshold", "answerable found", "unanswerable leaked", "verdict");
        System.out.println("-".repeat(78));

        double bestUsable = -1;
        double lowestWithNoLeak = -1;
        int previousFound = Integer.MAX_VALUE;
        int previousLeaked = Integer.MAX_VALUE;

        for (double threshold : CANDIDATES) {
            int found = countRetrieving(ANSWERABLE, threshold);
            int leaked = countRetrieving(UNANSWERABLE, threshold);

            String verdict;
            if (leaked > 0) {
                verdict = "answers questions it should refuse";
            } else if (found < ANSWERABLE.size()) {
                verdict = "refuses %d answerable question(s)".formatted(ANSWERABLE.size() - found);
            } else {
                verdict = "full recall, no leakage";
                bestUsable = Math.max(bestUsable, threshold);
            }
            if (leaked == 0 && lowestWithNoLeak < 0) {
                lowestWithNoLeak = threshold;
            }

            System.out.printf("%-11.2f %-22s %-24s %s%n",
                    threshold,
                    "%d / %d".formatted(found, ANSWERABLE.size()),
                    "%d / %d".formatted(leaked, UNANSWERABLE.size()),
                    verdict);

            // Raising the threshold can only ever admit fewer chunks. If this
            // is violated, the threshold is not actually being applied by the
            // query - which is the failure this harness is really guarding.
            assertThat(found)
                    .as("recall must not increase as the threshold rises (at %.2f)", threshold)
                    .isLessThanOrEqualTo(previousFound);
            assertThat(leaked)
                    .as("leakage must not increase as the threshold rises (at %.2f)", threshold)
                    .isLessThanOrEqualTo(previousLeaked);
            previousFound = found;
            previousLeaked = leaked;
        }

        System.out.println("-".repeat(78));
        System.out.printf("Lowest threshold with zero leakage:            %.2f%n", lowestWithNoLeak);
        System.out.printf("Highest threshold with full recall, no leak:   %s%n",
                bestUsable < 0 ? "none (see note below)" : "%.2f".formatted(bestUsable));
        System.out.printf("Configured value:                             %.2f%n", 0.62);

        if (bestUsable < 0) {
            System.out.println("""

                    No single threshold both answers everything and refuses everything.
                    Against the offline stub embedder that is the expected result and says
                    nothing about production: the stub matches on shared words, so a question
                    phrased differently from the document ("how many days of casual leave"
                    vs. a table row reading "Casual leave | 12 days") scores near zero no
                    matter where the bar is. Re-run with a real embedding model configured to
                    get a number that means something.""");
        }

        assertThat(lowestWithNoLeak)
                .as("some threshold in the swept range must refuse every out-of-scope question; "
                        + "if none does, the refusal path cannot be made safe by tuning")
                .isGreaterThan(0);
    }

    /** How many of these questions retrieve at least one chunk at this threshold. */
    private int countRetrieving(List<String> questions, double threshold) {
        int count = 0;
        for (String question : questions) {
            List<RetrievedChunk> hits = search.search(
                    TenantId.of(TENANT), embeddingModel.embed(question), null, 5, threshold);
            if (!hits.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void ingestSampleCorpus() throws IOException {
        Path samples = Path.of("samples");
        assertThat(Files.isDirectory(samples))
                .as("run from the project root; the sample corpus lives in ./samples")
                .isTrue();

        record Sample(String file, String category) {}
        List<Sample> corpus = List.of(
                new Sample("fee-policy.md", "FEES"),
                new Sample("transport-rules.md", "TRANSPORT"),
                new Sample("hr-leave-policy.md", "HR"),
                new Sample("exam-circular.md", "EXAM"));

        for (Sample sample : corpus) {
            uploadAndWait(TENANT, sample.file(),
                    Files.readString(samples.resolve(sample.file())), sample.category());
        }
    }
}
