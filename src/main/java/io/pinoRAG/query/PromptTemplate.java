package io.pinoRAG.query;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Default system + user prompt for v0. Collection-specific overrides land
// later via collection.settings JSON; for now every query uses the same
// template so the LLM's behaviour is predictable in tests.
//
// Substitution is single-pass over the placeholder regex. A naive
// chained String.replace would let a chunk body containing the literal
// "{{question}}" leak the user question into a context slot. The Matcher
// loop here only ever consumes literal placeholder spans from the template,
// never from the substituted values.
@Component
public class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

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
        return render(USER, Map.of(
                "context",  context  == null ? "" : context,
                "question", question == null ? "" : question));
    }

    static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.getOrDefault(key, m.group());
            // appendReplacement treats $ and \ specially; quote the
            // replacement so user input never gets interpreted.
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
