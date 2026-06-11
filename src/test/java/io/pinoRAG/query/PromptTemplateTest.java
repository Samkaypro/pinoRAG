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
}
