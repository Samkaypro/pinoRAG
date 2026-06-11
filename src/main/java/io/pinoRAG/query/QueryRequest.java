package io.pinoRAG.query;

import io.pinoRAG.retrieval.RetrievalMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record QueryRequest(
        @NotNull Long collectionId,
        @NotBlank String question,
        @Min(1) @Max(50) Integer k,
        Double minScore,
        // Optional per-request retrieval mode override.
        RetrievalMode mode
) {
}
