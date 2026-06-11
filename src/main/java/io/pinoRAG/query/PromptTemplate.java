package io.pinoRAG.query;

import org.springframework.stereotype.Component;

// Default system + user prompt for v0. Collection-specific overrides land
// in later via collection.settings JSON; for now every query uses
// the same template so the LLM's behaviour is predictable in tests.
@Component
public class PromptTemplate {

    private static final String SYSTEM = """
            You are pinoRAG, an assistant that answers questions using only
            the provided context. If the context does not contain the answer,
            say "I do not know based on the provided context."
            When you use a chunk, cite it inline as [chunk-N] where N is the id.
            """;

    private static final String USER = """
            Context:
            {{context}}
            Question: {{question}}

            Answer:""";

    public String system() {
        return SYSTEM;
    }

    public String user(String context, String question) {
        return USER.replace("{{context}}", context == null ? "" : context)
                .replace("{{question}}", question == null ? "" : question);
    }
}
