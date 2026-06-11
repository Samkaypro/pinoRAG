package io.pinoRAG.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

// Logback ClassicConverter that scrubs common secret shapes from each
// log line BEFORE it leaves the process. Registered in logback-spring.xml
// as a conversion word (%masked) and wired into the pattern.
//
// Patterns covered:
//   - "Bearer <token>" -> "Bearer [REDACTED]"
//   - OpenAI keys "sk-..." -> "sk-[REDACTED]"
//   - Generic "api_key=...", "apikey=...", "token=..." form fields
//
// Adding patterns: extend SECRETS. Order does not matter; each pattern is
// applied independently to the formatted message.
public class SecretMaskingConverter extends ClassicConverter {

    private static final Pattern[] SECRETS = new Pattern[]{
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+"),
            Pattern.compile("sk-[A-Za-z0-9_\\-]{20,}"),
            Pattern.compile("(?i)(api[_-]?key|token)=([A-Za-z0-9._\\-]+)")
    };
    private static final String[] REPLACEMENTS = new String[]{
            "Bearer [REDACTED]",
            "sk-[REDACTED]",
            "$1=[REDACTED]"
    };

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) return "";
        for (int i = 0; i < SECRETS.length; i++) {
            message = SECRETS[i].matcher(message).replaceAll(REPLACEMENTS[i]);
        }
        return message;
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) return message;
        String out = message;
        for (int i = 0; i < SECRETS.length; i++) {
            out = SECRETS[i].matcher(out).replaceAll(REPLACEMENTS[i]);
        }
        return out;
    }
}
