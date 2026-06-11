package io.pinoRAG.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScrubberTest {

    private final PiiScrubber scrubber = new PiiScrubber();

    @Test
    void redactsEmailAddresses() {
        String out = scrubber.scrub("contact us at alice.smith@example.co.uk for help");
        assertThat(out).contains("[REDACTED]").doesNotContain("alice.smith@example.co.uk");
    }

    @Test
    void redactsUsSsn() {
        String out = scrubber.scrub("SSN on file: 123-45-6789.");
        assertThat(out).contains("[REDACTED]").doesNotContain("123-45-6789");
    }

    @Test
    void redactsCreditCard() {
        String out = scrubber.scrub("card 4242 4242 4242 4242 expires soon");
        assertThat(out).contains("[REDACTED]").doesNotContain("4242 4242 4242 4242");
    }

    @Test
    void redactsCreditCardWithHyphens() {
        String out = scrubber.scrub("card 4242-4242-4242-4242 expires soon");
        assertThat(out).contains("[REDACTED]").doesNotContain("4242-4242-4242-4242");
    }

    @Test
    void redactsUsStylePhoneNumbers() {
        // Three 3-4 digit groups separated by space, dot, or hyphen.
        // International formats with 2-digit area codes (UK "20 7946 0958")
        // are NOT covered yet; that is documented in PiiScrubber javadoc
        // and tracked as a v1.1 follow-up.
        assertThat(scrubber.scrub("call 555-123-4567 today"))
                .contains("[REDACTED]").doesNotContain("555-123-4567");
        assertThat(scrubber.scrub("reach me at (415) 555-1212 today"))
                .contains("[REDACTED]").doesNotContain("415")
                .doesNotContain("555-1212");
        assertThat(scrubber.scrub("dot form 415.555.1212 works"))
                .contains("[REDACTED]").doesNotContain("415.555.1212");
    }

    @Test
    void leavesNonPiiAlone() {
        // No PII -> unchanged.
        String text = "The pricing policy charges customers monthly per active seat.";
        assertThat(scrubber.scrub(text)).isEqualTo(text);
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(scrubber.scrub(null)).isNull();
        assertThat(scrubber.scrub("")).isEmpty();
    }

    @Test
    void multipleCategoriesInSameString() {
        String mixed = "Reach Jane at jane@acme.io or (415) 555-1212; SSN 999-12-3456.";
        String out = scrubber.scrub(mixed);
        assertThat(out).contains("[REDACTED]")
                .doesNotContain("jane@acme.io")
                .doesNotContain("415")
                .doesNotContain("999-12-3456");
    }
}
