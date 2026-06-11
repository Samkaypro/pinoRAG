package io.pinoRAG.retrieval;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Runs the vector and BM25 retrievers in parallel on virtual threads,
// then fuses their ranked outputs via RRF. Each contributing retriever is
// asked for k * OVERSAMPLE so RRF has more material to discriminate from;
// the merged list is trimmed back to k.
@Component
public class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private static final int OVERSAMPLE = 2;

    private final VectorRetriever vector;
    private final BM25Retriever bm25;
    private final RrfMerger merger;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HybridRetriever(VectorRetriever vector, BM25Retriever bm25, RrfMerger merger) {
        this.vector = vector;
        this.bm25 = bm25;
        this.merger = merger;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public RetrievalMode mode() {
        return RetrievalMode.HYBRID;
    }

    @Override
    public List<ScoredChunk> search(Long tenantId, Long collectionId, RetrievalQuery query, int k) {
        int oversampled = Math.max(k, k * OVERSAMPLE);

        CompletableFuture<List<ScoredChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> safeSearch(vector, tenantId, collectionId, query, oversampled), executor);
        CompletableFuture<List<ScoredChunk>> bm25Future = CompletableFuture.supplyAsync(
                () -> safeSearch(bm25, tenantId, collectionId, query, oversampled), executor);

        try {
            CompletableFuture.allOf(vectorFuture, bm25Future).join();
            return merger.merge(List.of(vectorFuture.get(), bm25Future.get()), k);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException ex) {
            log.warn("Hybrid retrieval branch failed", ex.getCause());
            return List.of();
        }
    }

    private static List<ScoredChunk> safeSearch(Retriever r, Long tenantId, Long collectionId,
                                                RetrievalQuery query, int k) {
        try {
            return r.search(tenantId, collectionId, query, k);
        } catch (Exception ex) {
            log.warn("Branch {} failed; continuing with the other lane", r.mode(), ex);
            return List.of();
        }
    }
}
