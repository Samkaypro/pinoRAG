// LLM provider SPI.
//
// LlmClient is callback-shaped to make streaming the natural fit:
//   client.stream(request, consumer)
// consumer receives onToken(text), onComplete(), or onError(throwable).
//
// Active impl is picked by pinorag.llm.id at boot. Ships with FakeLlmClient
// (deterministic, used in tests) and OllamaLlmClient (Spring AI ChatModel).
// Add a provider by implementing LlmClient and tagging it with id().
package io.pinoRAG.llm;
