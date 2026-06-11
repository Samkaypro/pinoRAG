package io.pinoRAG.retrieval;

import java.util.List;

// Common shape for any retrieval backend (vector, BM25, hybrid, future
// reranker layers). The score field on each ScoredChunk is comparable
// WITHIN one retriever; cross-mode comparison only makes sense via the
// rank-based RRF fusion.
public interface Retriever {

    RetrievalMode mode();

    List<ScoredChunk> search(Long tenantId, Long collectionId, RetrievalQuery query, int k);
}
