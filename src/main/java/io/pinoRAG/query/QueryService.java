package io.pinoRAG.query;

import io.pinoRAG.ingest.embed.EmbedderSelector;
import io.pinoRAG.llm.LlmClientSelector;
import io.pinoRAG.llm.LlmRequest;
import io.pinoRAG.llm.TokenConsumer;
import io.pinoRAG.retrieval.ContextAssembler;
import io.pinoRAG.retrieval.RetrievalMode;
import io.pinoRAG.retrieval.RetrievalQuery;
import io.pinoRAG.retrieval.Retrievers;
import io.pinoRAG.retrieval.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Query orchestrator. Tenant context is passed in as explicit args because
// this code runs on a virtual thread spawned by the controller; the request
// scope is not visible here. Status events fire first to keep TTFT low.
// Citations land before the first token so a UI can render sources during
// generation. Query log writes happen AFTER the stream so a logging failure
// never aborts the answer.
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final EmbedderSelector embedders;
    private final LlmClientSelector llms;
    private final Retrievers retrievers;
    private final ContextAssembler assembler;
    private final PromptTemplate template;
    private final QueryProperties props;
    private final JdbcTemplate jdbc;

    public QueryService(EmbedderSelector embedders,
                        LlmClientSelector llms,
                        Retrievers retrievers,
                        ContextAssembler assembler,
                        PromptTemplate template,
                        QueryProperties props,
                        JdbcTemplate jdbc) {
        this.embedders = embedders;
        this.llms = llms;
        this.retrievers = retrievers;
        this.assembler = assembler;
        this.template = template;
        this.props = props;
        this.jdbc = jdbc;
    }

    public void run(QueryRequest request, Long tenantId, Long apiKeyId,
                    String subject, String[] groups, SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();

        try {
            emit(emitter, "status", new SseEventPayloads.Status("retrieving"));

            int k = request.k() == null ? props.topK() : request.k();
            double minScore = request.minScore() == null ? props.minScore() : request.minScore();
            RetrievalMode mode = request.mode() == null ? props.retrievalMode() : request.mode();

            // Embed only when the chosen mode actually needs a vector.
            // BM25 reads from the text field; computing the embedding for
            // that path would be a wasted round-trip to the embedder.
            float[] queryVector = needsEmbedding(mode)
                    ? embedders.active().embed(List.of(request.question())).get(0)
                    : null;
            RetrievalQuery retrievalQuery = new RetrievalQuery(
                    request.question(), queryVector, subject, groups);
            List<ScoredChunk> ranked = retrievers.forMode(mode).search(
                    tenantId, request.collectionId(), retrievalQuery, k);

            List<ScoredChunk> passing = ranked.stream()
                    .filter(c -> c.score() >= minScore)
                    .toList();

            if (passing.isEmpty()) {
                runFallback(emitter, request);
                logQuery(tenantId, apiKeyId, request.question(), List.of(),
                        System.currentTimeMillis() - startedAt);
                emit(emitter, "done", new SseEventPayloads.Done(
                        System.currentTimeMillis() - startedAt, List.of()));
                emitter.complete();
                return;
            }

            ContextAssembler.Result ctx = assembler.assemble(passing, props.contextTokenBudget());
            for (ScoredChunk c : ctx.includedChunks()) {
                emit(emitter, "citation", SseEventPayloads.Citation.of(c));
            }

            emit(emitter, "status", new SseEventPayloads.Status("generating"));

            LlmRequest llmReq = new LlmRequest(
                    template.system(),
                    template.user(ctx.context(), request.question()),
                    props.maxAnswerTokens(),
                    props.temperature());

            List<Long> usedIds = ctx.includedChunks().stream().map(ScoredChunk::chunkId).toList();
            streamTokens(emitter, llmReq, startedAt, usedIds);
            logQuery(tenantId, apiKeyId, request.question(), usedIds,
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException ex) {
            log.error("Query failed", ex);
            tryEmitError(emitter, "INTERNAL", "Query failed");
            emitter.completeWithError(ex);
        }
    }

    private void streamTokens(SseEmitter emitter, LlmRequest req,
                              long startedAt, List<Long> usedIds) {
        llms.active().stream(req, new TokenConsumer() {
            @Override public void onToken(String text) {
                emit(emitter, "token", new SseEventPayloads.Token(text));
            }
            @Override public void onComplete() {
                emit(emitter, "done", new SseEventPayloads.Done(
                        System.currentTimeMillis() - startedAt, usedIds));
                emitter.complete();
            }
            @Override public void onError(Throwable t) {
                log.error("LLM stream failed", t);
                tryEmitError(emitter, "LLM_FAILED", "LLM stream failed");
                emitter.completeWithError(t);
            }
        });
    }

    private void runFallback(SseEmitter emitter, QueryRequest req) {
        switch (props.fallbackMode()) {
            case REFUSE -> emit(emitter, "token", new SseEventPayloads.Token(
                    "I do not have any relevant context to answer that."));
            case MESSAGE -> emit(emitter, "token", new SseEventPayloads.Token(props.fallbackMessage()));
            case LLM_ONLY -> runLlmOnlyFallback(emitter, req);
        }
    }

    private void runLlmOnlyFallback(SseEmitter emitter, QueryRequest req) {
        LlmRequest noCtx = new LlmRequest(template.system(),
                template.user("", req.question()),
                props.maxAnswerTokens(), props.temperature());
        CountDownLatch latch = new CountDownLatch(1);
        llms.active().stream(noCtx, new TokenConsumer() {
            @Override public void onToken(String text) {
                emit(emitter, "token", new SseEventPayloads.Token(text));
            }
            @Override public void onComplete() { latch.countDown(); }
            @Override public void onError(Throwable t) {
                log.error("LLM_ONLY fallback failed", t);
                latch.countDown();
            }
        });
        try {
            // Bounded wait so a stuck LLM does not leak the virtual thread
            // until the SseEmitter's own timeout fires.
            if (!latch.await(props.llmTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("LLM_ONLY fallback exceeded {} ms", props.llmTimeoutMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException ex) {
            log.debug("SSE emit failed (client likely disconnected)", ex);
            throw new IllegalStateException(ex);
        }
    }

    private static void tryEmitError(SseEmitter emitter, String code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(new SseEventPayloads.ErrorPayload(code, message)));
        } catch (Exception ignored) {
        }
    }

    private void logQuery(Long tenantId, Long apiKeyId, String question,
                          List<Long> retrievedChunkIds, long latencyMs) {
        try {
            String hash = sha256Hex(question == null ? "" : question);
            jdbc.update(con -> {
                Array array = con.createArrayOf("BIGINT", retrievedChunkIds.toArray());
                var ps = con.prepareStatement(
                        "INSERT INTO pino_query_log " +
                                "(tenant_id, api_key_id, query_hash, retrieved_chunk_ids, latency_ms) " +
                                "VALUES (?, ?, ?, ?, ?)");
                ps.setLong(1, tenantId);
                if (apiKeyId == null) ps.setNull(2, java.sql.Types.BIGINT);
                else ps.setLong(2, apiKeyId);
                ps.setString(3, hash);
                ps.setArray(4, array);
                ps.setInt(5, (int) Math.min(latencyMs, Integer.MAX_VALUE));
                return ps;
            });
        } catch (Exception ex) {
            log.warn("Failed to write query log", ex);
        }
    }

    private static boolean needsEmbedding(RetrievalMode mode) {
        return mode == RetrievalMode.VECTOR || mode == RetrievalMode.HYBRID;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }
}
