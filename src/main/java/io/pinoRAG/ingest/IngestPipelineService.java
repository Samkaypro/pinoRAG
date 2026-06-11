package io.pinoRAG.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pinoRAG.collection.CollectionSettings;
import io.pinoRAG.collection.CollectionSettingsLookup;
import io.pinoRAG.ingest.chunking.ChunkingStrategySelector;
import io.pinoRAG.ingest.embed.Embedder;
import io.pinoRAG.ingest.embed.EmbedderSelector;
import io.pinoRAG.ingest.parse.ParserRouter;
import io.pinoRAG.ingest.write.ChunkWriter;
import io.pinoRAG.ingest.write.EmbeddingWriter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IngestPipelineService.class);

    private final DocumentStorage storage;
    private final ParserRouter parsers;
    private final ChunkingStrategySelector chunkers;
    private final EmbedderSelector embedders;
    private final ChunkWriter chunkWriter;
    private final EmbeddingWriter embeddingWriter;
    private final JdbcTemplate jdbc;
    private final IngestProperties props;
    private final MeterRegistry meters;
    private final PiiScrubber pii;
    private final CollectionSettingsLookup settingsLookup;

    // Tracked so graceful shutdown can wait for in-flight pipelines before
    // returning from @PreDestroy. Documents still in PROCESSING after the
    // wait timeout get reset to PENDING so a restarted node can pick them up.
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile boolean shuttingDown = false;

    public IngestPipelineService(DocumentStorage storage,
                                 ParserRouter parsers,
                                 ChunkingStrategySelector chunkers,
                                 EmbedderSelector embedders,
                                 ChunkWriter chunkWriter,
                                 EmbeddingWriter embeddingWriter,
                                 JdbcTemplate jdbc,
                                 IngestProperties props,
                                 MeterRegistry meters,
                                 PiiScrubber pii,
                                 CollectionSettingsLookup settingsLookup) {
        this.storage = storage;
        this.parsers = parsers;
        this.chunkers = chunkers;
        this.embedders = embedders;
        this.chunkWriter = chunkWriter;
        this.embeddingWriter = embeddingWriter;
        this.jdbc = jdbc;
        this.props = props;
        this.meters = meters;
        this.pii = pii;
        this.settingsLookup = settingsLookup;
    }

    @Async
    public void startAsync(IngestRequest req) {
        if (shuttingDown) {
            log.warn("Ingest dispatch refused: shutting down. doc={}", req.documentId());
            return;
        }
        inFlight.incrementAndGet();
        try {
            runPipeline(req);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    void runPipeline(IngestRequest req) {
        markStatus(req.documentId(), "PROCESSING", null);

        String text;
        try {
            text = timed("parse", () -> {
                try (InputStream in = storage.open(req.tenantId(), req.collectionId(),
                        req.documentUuid(), req.version())) {
                    return parsers.route(req.mimeType()).extract(in, req.mimeType());
                }
            });
        } catch (Exception ex) {
            recordFailure(req, "PARSE", IngestErrorCode.PARSE_FAILED, ex);
            return;
        }

        // PII scrub happens AFTER parse and BEFORE chunk so the redaction
        // surfaces in pino_chunks.body and in every downstream search hit.
        CollectionSettings settings = settingsLookup.forCollection(req.tenantId(), req.collectionId());
        if (pii.isEnabledFor(settings)) {
            text = pii.scrub(text);
            logStage(req, "SCRUB", "SUCCESS", 0, null);
        }

        List<String> chunks;
        try {
            final String textToChunk = text; // effectively-final copy required by lambda
            chunks = timed("chunk", () -> chunkers.active().chunk(textToChunk));
        } catch (Exception ex) {
            recordFailure(req, "CHUNK", IngestErrorCode.CHUNK_FAILED, ex);
            return;
        }
        logStage(req, "CHUNK", "SUCCESS", 0, "count=" + chunks.size());

        if (chunks.isEmpty()) {
            markStatus(req.documentId(), "READY", null);
            meters.counter("pinorag.ingest.completed", "outcome", "empty").increment();
            return;
        }

        Embedder embedder = embedders.active();
        int batch = Math.max(1, props.embeddingBatchSize());

        List<Long> chunkIds;
        try {
            chunkIds = chunkWriter.writeAll(req.tenantId(), req.collectionId(),
                    req.documentId(), chunks);
        } catch (Exception ex) {
            recordFailure(req, "PERSIST", IngestErrorCode.PERSIST_FAILED, ex);
            return;
        }

        try {
            timed("embed", () -> {
                for (int start = 0; start < chunks.size(); start += batch) {
                    int end = Math.min(start + batch, chunks.size());
                    List<String> slice = new ArrayList<>(chunks.subList(start, end));
                    List<float[]> vectors = embedder.embed(slice);
                    List<Long> idSlice = chunkIds.subList(start, end);
                    embeddingWriter.writeAll(idSlice, vectors, embedder.id(), embedder.dimensions());
                }
                return null;
            });
        } catch (Exception ex) {
            recordFailure(req, "EMBED", IngestErrorCode.EMBED_FAILED, ex);
            return;
        }

        markStatus(req.documentId(), "READY", null);
        meters.counter("pinorag.ingest.completed", "outcome", "ready").increment();
    }

    private <T> T timed(String stage, ThrowingSupplier<T> action) throws Exception {
        Timer.Sample s = Timer.start(meters);
        try {
            T out = action.get();
            s.stop(meters.timer("pinorag.ingest.stage", "stage", stage, "outcome", "ok"));
            return out;
        } catch (Exception ex) {
            s.stop(meters.timer("pinorag.ingest.stage", "stage", stage, "outcome", "fail"));
            throw ex;
        }
    }

    private void recordFailure(IngestRequest req, String stage,
                               IngestErrorCode code, Exception ex) {
        log.error("Ingest stage {} failed for doc {}: {}",
                stage, req.documentId(), ex.getMessage(), ex);
        markStatus(req.documentId(), "FAILED", code.name());
        logStage(req, stage, "FAILED", 0, truncate(safeMessage(ex), 4000));
        meters.counter("pinorag.ingest.completed", "outcome", "failed", "stage", stage).increment();
    }

    private void markStatus(Long docId, String status, String errorCode) {
        jdbc.update("UPDATE pino_documents SET status = ?, error = ?, updated_at = ? WHERE id = ?",
                status, errorCode, OffsetDateTime.now(), docId);
    }

    private void logStage(IngestRequest req, String stage, String status, long latencyMs, String msg) {
        try {
            jdbc.update("INSERT INTO pino_ingest_log " +
                            "(tenant_id, document_id, stage, status, latency_ms, message) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    req.tenantId(), req.documentId(), stage, status,
                    (int) Math.min(latencyMs, Integer.MAX_VALUE),
                    msg);
        } catch (Exception ignored) {
            // never let audit failure mask the pipeline outcome
        }
    }

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
        long waitedMillis = 0;
        long limit = 30_000;
        long step = 200;
        while (inFlight.get() > 0 && waitedMillis < limit) {
            try { Thread.sleep(step); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waitedMillis += step;
        }
        // Anything still PROCESSING after the wait goes back to PENDING so a
        // restarted node can resume it. We deliberately reset only rows whose
        // updated_at is older than 5 seconds so we don't race a thread that
        // just started.
        int reset = jdbc.update(
                "UPDATE pino_documents SET status = 'PENDING' " +
                        "WHERE status = 'PROCESSING' AND updated_at < now() - interval '5 seconds'");
        if (reset > 0) {
            log.warn("Reset {} PROCESSING documents to PENDING during shutdown", reset);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static String safeMessage(Exception ex) {
        String m = ex.getMessage();
        return m == null ? ex.getClass().getSimpleName() : m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
