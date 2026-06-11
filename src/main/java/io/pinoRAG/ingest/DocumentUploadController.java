package io.pinoRAG.ingest;

import io.pinoRAG.document.DocumentResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/collections")
public class DocumentUploadController {

    private final DocumentUploadService uploads;

    public DocumentUploadController(DocumentUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping(value = "/{collectionId}/documents",
            consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> upload(@PathVariable Long collectionId,
                                                   @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var doc = uploads.uploadMultipart(collectionId, file);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(DocumentResponse.from(doc, 0L));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Void> notFound(EntityNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(DocumentUploadService.ConcurrentUploadException.class)
    public ResponseEntity<Void> conflict(DocumentUploadService.ConcurrentUploadException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
