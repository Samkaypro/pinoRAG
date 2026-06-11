package io.pinoRAG.ingest.parse;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

// Apache Tika handles PDF, DOCX, HTML, Markdown, plain text. We use the
// auto-detect parser and a body-only SAX handler to skip metadata noise.
@Component
public class TikaParser implements DocumentParser {

    private static final int LIMIT = 10_000_000;

    @Override
    public String id() {
        return "tika";
    }

    @Override
    public boolean supports(String mimeType) {
        return true;
    }

    @Override
    public String extract(InputStream source, String mimeType) throws IOException, TikaException, SAXException {
        BodyContentHandler handler = new BodyContentHandler(LIMIT);
        Metadata metadata = new Metadata();
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        }
        AutoDetectParser parser = new AutoDetectParser();
        parser.parse(source, handler, metadata, new ParseContext());
        return handler.toString();
    }
}
