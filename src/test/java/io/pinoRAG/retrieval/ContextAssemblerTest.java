package io.pinoRAG.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    void packsHighScoreChunksFirst() {
        List<ScoredChunk> ranked = List.of(
                chunk(1L, "alpha", 0.95),
                chunk(2L, "beta",  0.85),
                chunk(3L, "gamma", 0.70));
        ContextAssembler.Result r = assembler.assemble(ranked, 10_000);

        assertThat(r.includedChunks()).extracting(ScoredChunk::chunkId)
                .containsExactly(1L, 2L, 3L);
        assertThat(r.context()).contains("[chunk-1]").contains("[chunk-2]").contains("[chunk-3]");
    }

    @Test
    void stopsWhenTokenBudgetWouldOverflow() {
        // 100 chars per chunk -> ~25 tokens per chunk at chars/4.
        // Budget of 60 tokens fits 2 chunks but not 3.
        List<ScoredChunk> ranked = List.of(
                chunk(1L, "a".repeat(100), 0.9),
                chunk(2L, "b".repeat(100), 0.85),
                chunk(3L, "c".repeat(100), 0.8));
        ContextAssembler.Result r = assembler.assemble(ranked, 60);

        assertThat(r.includedChunks()).hasSize(2);
        assertThat(r.context()).contains("[chunk-1]").contains("[chunk-2]")
                .doesNotContain("[chunk-3]");
    }

    @Test
    void emptyInputProducesEmptyResult() {
        ContextAssembler.Result r = assembler.assemble(List.of(), 1000);
        assertThat(r.context()).isEmpty();
        assertThat(r.includedChunks()).isEmpty();
        assertThat(r.approxTokens()).isZero();
    }

    @Test
    void respectsZeroBudget() {
        ContextAssembler.Result r = assembler.assemble(
                List.of(chunk(1L, "anything", 1.0)), 0);
        assertThat(r.includedChunks()).isEmpty();
    }

    private static ScoredChunk chunk(long id, String body, double score) {
        return new ScoredChunk(id, 100L + id, "doc-" + id + ".txt", body, score);
    }
}
