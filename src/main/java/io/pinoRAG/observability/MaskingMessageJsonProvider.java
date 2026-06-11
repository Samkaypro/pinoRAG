package io.pinoRAG.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

import java.io.IOException;

// Replaces logstash-logback-encoder's default MessageJsonProvider with one
// that routes the formatted message through SecretMaskingConverter. Wired
// in logback-spring.xml prod profile so the same patterns that get masked
// in the dev pattern layout also get masked in the JSON output.
public class MaskingMessageJsonProvider extends MessageJsonProvider {

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        String masked = SecretMaskingConverter.mask(event.getFormattedMessage());
        generator.writeStringField(getFieldName(), masked == null ? "" : masked);
    }
}
