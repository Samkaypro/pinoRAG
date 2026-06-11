package io.pinoRAG.retrieval;

// Carries both representations of the user question. Vector retrieval needs
// the embedding; BM25 needs the raw text; hybrid needs both. Building this
// once at the controller boundary keeps the retriever interface simple.
public record RetrievalQuery(
        String text,
        float[] embedding
) {
}
