// Chunking strategies.
//
// Interface + impls + selector. Active strategy is picked by
// pinorag.chunker.id at boot. Add a new strategy by implementing
// ChunkingStrategy.id() and ChunkingStrategy.chunk(text).
package io.pinoRAG.ingest.chunking;
