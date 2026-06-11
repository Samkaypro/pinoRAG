package io.pinoRAG.ingest.parse;

import java.io.InputStream;

public interface DocumentParser {

    String id();

    boolean supports(String mimeType);

    String extract(InputStream source, String mimeType) throws Exception;
}
