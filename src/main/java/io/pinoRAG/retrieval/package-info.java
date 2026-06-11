// Retrieval - the read side of pgvector.
//
// VectorRetriever runs pgvector cosine search scoped to the caller's
// tenant + collection. ScoredChunk is the unit of retrieval: chunk id +
// document metadata + similarity score. Hybrid (BM25 + RRF) lands in
package io.pinoRAG.retrieval;
