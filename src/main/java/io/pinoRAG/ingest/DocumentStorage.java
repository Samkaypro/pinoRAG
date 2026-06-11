package io.pinoRAG.ingest;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

// Lays out: {root}/tenant-{tid}/{collectionId}/{docUuid}/v{version}/original
// We keep the original bytes so re-indexing is possible without re-uploading.
@Component
public class DocumentStorage {

    private final Path root;

    public DocumentStorage(IngestProperties props) {
        this.root = Path.of(props.uploadDir());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create upload dir " + root, e);
        }
    }

    public Path persist(Long tenantId, Long collectionId, UUID docUuid, int version, MultipartFile file) {
        Path target = pathFor(tenantId, collectionId, docUuid, version);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new StorageException("Failed to persist upload for doc " + docUuid, e);
        }
    }

    public InputStream open(Long tenantId, Long collectionId, UUID docUuid, int version) {
        Path target = pathFor(tenantId, collectionId, docUuid, version);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new StorageException("Failed to open stored doc " + docUuid, e);
        }
    }

    public Path pathFor(Long tenantId, Long collectionId, UUID docUuid, int version) {
        return root.resolve("tenant-" + tenantId)
                .resolve(String.valueOf(collectionId))
                .resolve(docUuid.toString())
                .resolve("v" + version)
                .resolve("original");
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String msg, Throwable cause) { super(msg, cause); }
    }
}
