package io.pinoRAG.ingest;

// Pure function so it can be unit tested without Spring. Strips path-traversal
// sequences, control characters, and oversized names. Returns a safe default
// when the input is null, blank, or fully reduced to nothing.
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 200;
    private static final String DEFAULT_NAME = "upload";

    private FilenameSanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null) return DEFAULT_NAME;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_NAME;

        String stripped = stripPathSegments(trimmed);
        stripped = stripped.replaceAll("[\\\\/:*?\"<>|]", "_");
        stripped = stripped.replaceAll("\\p{Cntrl}", "");
        stripped = stripped.replaceAll("^[.\\s]+", "");
        stripped = stripped.replaceAll("[.\\s]+$", "");

        if (stripped.isEmpty() || stripped.equals(".") || stripped.equals("..")) {
            return DEFAULT_NAME;
        }
        if (stripped.length() > MAX_LENGTH) {
            stripped = stripped.substring(0, MAX_LENGTH);
        }
        return stripped;
    }

    // Take only the final segment so values like "../../etc/passwd" or
    // "C:\Windows\System32\config" lose every path component.
    private static String stripPathSegments(String s) {
        int sep = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return sep < 0 ? s : s.substring(sep + 1);
    }
}
