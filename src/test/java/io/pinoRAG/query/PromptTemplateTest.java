package io.pinoRAG.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateTest {

    private final PromptTemplate template = new PromptTemplate();

    @Test
    void rendersBothPlaceholders() {
        String rendered = template.user("CTX_BODY", "QUESTION_BODY");
        assertThat(rendered).contains("CTX_BODY").contains("QUESTION_BODY")
                .doesNotContain("{{context}}").doesNotContain("{{question}}");
    }

    @Test
    void nullInputsBecomeEmptyStrings() {
        String rendered = template.user(null, null);
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    void systemPromptForbidsHallucination() {
        String sys = template.system();
        assertThat(sys)
                .contains("I do not know")
                .contains("[chunk-N]");
    }

    @Test
    void contextValueContainingPlaceholderIsNotReinterpreted() {
        // A chunk body containing "{{question}}" must NOT be reinterpreted
        // as a placeholder during a second pass. A naive chained replace
        // would leak the user question into the context slot.
        String malicious = "OBVIOUSLY_FROM_CONTEXT {{question}} stillFromContext";
        String real      = "REAL_USER_QUESTION";
        String rendered  = template.user(malicious, real);

        // The literal substring lives in the rendered output and is NOT
        // replaced by the real question.
        assertThat(rendered).contains("OBVIOUSLY_FROM_CONTEXT {{question}} stillFromContext");
        // The real question still appears exactly once in its own slot.
        assertThat(rendered.split(java.util.regex.Pattern.quote(real)).length - 1).isEqualTo(1);
    }

    @Test
    void replacementValueWithDollarOrBackslashIsEscaped() {
        // Matcher.appendReplacement treats $1 and \\ specially. The render
        // must use quoteReplacement so user input cannot break parsing.
        String rendered = template.user("a $1 b \\$ c", "ok");
        assertThat(rendered).contains("a $1 b \\$ c");
    }
}
