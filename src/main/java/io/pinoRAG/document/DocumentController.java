package io.pinoRAG.document;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable Long id) {
        return documents.getForCaller(id);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Void> notFound(EntityNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }
}
