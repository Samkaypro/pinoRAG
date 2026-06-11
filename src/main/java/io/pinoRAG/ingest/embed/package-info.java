// Embedding providers.
//
// Interface + impls + selector. Active embedder is picked by
// pinorag.embedder.id at boot. Ships with HashingFakeEmbedder (tests) and
// OllamaEmbedder (production). OpenAI and Vertex impls land later
package io.pinoRAG.ingest.embed;
