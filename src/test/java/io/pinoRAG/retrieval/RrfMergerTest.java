package io.pinoRAG.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class RrfMergerTest {

    private final RrfMerger merger = new RrfMerger();

    @Test
    void singleRetrieverOrderingIsPreserved() {
        List<ScoredChunk> only = List.of(chunk(1), chunk(2), chunk(3));
        List<ScoredChunk> merged = merger.merge(List.of(only), 10);

        assertThat(merged).extracting(ScoredChunk::chunkId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void chunkAppearingInBothListsRanksHigher() {
        List<ScoredChunk> a = List.of(chunk(1), chunk(2), chunk(3));
        List<ScoredChunk> b = List.of(chunk(3), chunk(4));

        List<ScoredChunk> merged = merger.merge(List.of(a, b), 10);

        // Chunk 3 appears in both lists (rank 3 in a, rank 1 in b).
        // Chunk 1 appears only in a (rank 1).
        // RRF(3) = 1/(60+3) + 1/(60+1) ~ 0.0322
        // RRF(1) = 1/(60+1)            ~ 0.0164
        assertThat(merged.get(0).chunkId()).isEqualTo(3L);
    }

    @Test
    void rrfScoreMathMatchesFormula() {
        int k = RrfMerger.DEFAULT_K;
        List<ScoredChunk> a = List.of(chunk(1));
        List<ScoredChunk> b = List.of(chunk(1));

        List<ScoredChunk> merged = merger.merge(List.of(a, b), 1);

        double expected = 1.0 / (k + 1) + 1.0 / (k + 1);
        assertThat(merged.get(0).score()).isCloseTo(expected, offset(1e-9));
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(merger.merge(List.of(), 5)).isEmpty();
        assertThat(merger.merge(List.of(List.of(), List.of()), 5)).isEmpty();
        assertThat(merger.merge(null, 5)).isEmpty();
        assertThat(merger.merge(List.of(List.of(chunk(1))), 0)).isEmpty();
    }

    @Test
    void limitClampsOutputLength() {
        List<ScoredChunk> a = List.of(chunk(1), chunk(2), chunk(3), chunk(4));
        List<ScoredChunk> merged = merger.merge(List.of(a), 2);
        assertThat(merged).hasSize(2);
    }

    @Test
    void firstSeenSourceMetadataWins() {
        ScoredChunk fromA = new ScoredChunk(1L, 10L, "doc-a.txt", "body-a", 0.9);
        ScoredChunk fromB = new ScoredChunk(1L, 10L, "doc-b.txt", "body-b", 0.5);

        List<ScoredChunk> merged = merger.merge(List.of(List.of(fromA), List.of(fromB)), 1);

        assertThat(merged.get(0).documentName()).isEqualTo("doc-a.txt");
        assertThat(merged.get(0).body()).isEqualTo("body-a");
    }

    private static ScoredChunk chunk(long id) {
        return new ScoredChunk(id, 100L + id, "doc-" + id + ".txt", "body-" + id, 0.0);
    }
}
