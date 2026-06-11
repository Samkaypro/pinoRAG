package io.pinoRAG.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BM25RetrieverQueryRewriteTest {

    @Test
    void joinsTokensWithOrSoAnyTermSuffices() {
        assertThat(BM25Retriever.toOrQuery("what is the refund policy"))
                .isEqualTo("what OR is OR the OR refund OR policy");
    }

    @Test
    void stripsCharactersThatWouldConfuseTsquery() {
        // Quotes, semicolons, parens, ampersands all dropped before being
        // handed to websearch_to_tsquery. Tokens that empty out entirely
        // are skipped, not joined as empty.
        assertThat(BM25Retriever.toOrQuery("hello; & (world)"))
                .isEqualTo("hello OR world");
    }

    @Test
    void preservesHyphens() {
        assertThat(BM25Retriever.toOrQuery("rate-limit policy"))
                .isEqualTo("rate-limit OR policy");
    }

    @Test
    void blankInputProducesBlankOutput() {
        assertThat(BM25Retriever.toOrQuery("")).isEmpty();
        assertThat(BM25Retriever.toOrQuery("   ")).isEmpty();
    }

    @Test
    void unicodeWordCharactersAreKept() {
        // Postgres english config will normalize most of these into nothing,
        // but the rewrite must not throw or drop letters.
        assertThat(BM25Retriever.toOrQuery("café résumé naïve"))
                .isEqualTo("café OR résumé OR naïve");
    }
}
