package io.pinoRAG.ingest.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class PdfBoxParser implements DocumentParser {

    @Override
    public String id() {
        return "pdfbox";
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.equalsIgnoreCase("application/pdf");
    }

    @Override
    public String extract(InputStream source, String mimeType) throws IOException {
        byte[] bytes = source.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
