package io.pinoRAG.ingest;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Bridges the upload transaction and the @Async pipeline. The listener fires
// AFTER_COMMIT so the inserted pino_documents row is durable before the
// async thread tries to read it.
@Component
public class IngestEventListener {

    private final IngestPipelineService pipeline;

    public IngestEventListener(IngestPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestRequested(IngestRequestedEvent event) {
        pipeline.startAsync(event.request());
    }
}
