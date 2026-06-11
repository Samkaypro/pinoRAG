// Query feature.
//
// POST /v1/query returns text/event-stream. The flow is:
//   1. Emit status:retrieving immediately so first byte is fast.
//   2. Embed the question, vector search, score-threshold gate.
//   3. Emit one citation event per included chunk.
//   4. Render the prompt and stream LLM tokens as token events.
//   5. Emit done with latency and chunk ids.
//
// Owns: QueryRequest DTO, QueryProperties, QueryService orchestrator,
// QueryController, PromptTemplate.
package io.pinoRAG.query;
