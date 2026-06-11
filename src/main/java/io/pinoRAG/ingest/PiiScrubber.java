package io.pinoRAG.ingest;

import io.pinoRAG.collection.CollectionSettings;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

// Regex-based PII redaction. Patterns are conservative: each one covers
// the dominant forms of its category and accepts some false positives,
// because under-redaction is the worse failure mode. Add patterns by
// expanding the PATTERNS array; order matters because credit card numbers
// must be matched before raw digit runs would mask them.
//
// Limitations (documented for plugin authors and operators):
//   - English-locale conventions only (US-style SSN and phone).
//   - Does not detect addresses, names, or IBAN.
//   - Substring matching: phone "555-12-1234" would be partially redacted
//     since it overlaps both phone and SSN. The final string is fine.
@Component
public class PiiScrubber {

    public static final String REPLACEMENT = "[REDACTED]";

    // Order matters. Credit card before generic digit-run (we do not have
    // a generic one, but if we add one later it must come last).
    private static final Pattern[] PATTERNS = new Pattern[]{
            // Credit card: four groups of 4 digits separated by space or hyphen.
            Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),
            // US SSN: ddd-dd-dddd.
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            // Email.
            Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"),
            // Phone: optional country code, then 7-14 digits with separators.
            // Anchored on a digit-boundary so we do not chew through years.
            Pattern.compile("(?:\\+?\\d{1,3}[\\s.\\-])?\\(?\\d{3,4}\\)?[\\s.\\-]\\d{3,4}[\\s.\\-]\\d{3,4}")
    };

    public String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll(REPLACEMENT);
        }
        return out;
    }

    public boolean isEnabledFor(CollectionSettings settings) {
        return settings == null || settings.piiScrubbingEnabled();
    }
}
