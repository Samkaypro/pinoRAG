package io.pinoRAG.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingMessageJsonProviderTest {

    @Test
    void writesMaskedMessageField() throws IOException {
        ILoggingEvent event = newEvent(
                "calling upstream with Authorization: Bearer abc.def.ghi-123_456");

        String json = renderJson(event);

        // Must contain the masked form, must not contain the raw token.
        assertThat(json).contains("Bearer [REDACTED]");
        assertThat(json).doesNotContain("abc.def.ghi-123_456");
    }

    @Test
    void emptyOrNullMessageRendersEmptyString() throws IOException {
        assertThat(renderJson(newEvent(null))).contains("\"msg\":\"\"");
        assertThat(renderJson(newEvent("")))  .contains("\"msg\":\"\"");
    }

    @Test
    void plainMessageIsUntouched() throws IOException {
        String json = renderJson(newEvent("orchestrator started on virtual thread"));
        assertThat(json).contains("orchestrator started on virtual thread");
    }

    @Test
    void masksOpenAiKeyAndApiKeyFormField() throws IOException {
        String json = renderJson(newEvent(
                "POST /upload sk-abc123XYZ_456-789defGHI012jklMNO34 api_key=ZZZZZZZZZ"));
        assertThat(json).contains("sk-[REDACTED]");
        assertThat(json).contains("api_key=[REDACTED]");
        assertThat(json).doesNotContain("abc123XYZ_456");
        assertThat(json).doesNotContain("ZZZZZZZZZ");
    }

    // ----- helpers -----

    private static String renderJson(ILoggingEvent event) throws IOException {
        MaskingMessageJsonProvider provider = new MaskingMessageJsonProvider();
        provider.setFieldName("msg");
        StringWriter writer = new StringWriter();
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator generator = factory.createGenerator(writer)) {
            generator.writeStartObject();
            provider.writeTo(generator, event);
            generator.writeEndObject();
        }
        return writer.toString();
    }

    private static ILoggingEvent newEvent(String message) {
        // LoggingEvent.getFormattedMessage() returns the raw message when
        // argumentArray is null and the message has no {} placeholders.
        // That is everything the JsonProvider reads from the event.
        LoggingEvent e = new LoggingEvent();
        e.setLoggerName("io.pinoRAG.test");
        e.setMessage(message);
        return e;
    }
}
