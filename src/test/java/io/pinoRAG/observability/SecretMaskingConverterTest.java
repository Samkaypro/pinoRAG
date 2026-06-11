package io.pinoRAG.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskingConverterTest {

    @Test
    void masksBearerTokens() {
        String out = SecretMaskingConverter.mask(
                "calling upstream with Authorization: Bearer abc.def.ghi-123_456");
        assertThat(out).contains("Bearer [REDACTED]")
                .doesNotContain("abc.def.ghi-123_456");
    }

    @Test
    void masksOpenAiKeys() {
        String out = SecretMaskingConverter.mask(
                "using key sk-abc123XYZ_456-789defGHI012jklMNO34");
        assertThat(out).contains("sk-[REDACTED]")
                .doesNotContain("abc123XYZ_456-789defGHI012jklMNO34");
    }

    @Test
    void masksApiKeyFormFields() {
        String out = SecretMaskingConverter.mask(
                "POST /upload api_key=AKIAIOSFODNN7EXAMPLE&token=Z3RXJxF");
        assertThat(out).contains("api_key=[REDACTED]")
                .contains("token=[REDACTED]")
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .doesNotContain("Z3RXJxF");
    }

    @Test
    void leavesPlainMessagesAlone() {
        String text = "starting query orchestrator on virtual thread";
        assertThat(SecretMaskingConverter.mask(text)).isEqualTo(text);
    }

    @Test
    void nullAndEmptySafe() {
        assertThat(SecretMaskingConverter.mask(null)).isNull();
        assertThat(SecretMaskingConverter.mask("")).isEmpty();
    }
}
