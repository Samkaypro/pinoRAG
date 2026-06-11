package io.pinoRAG.query;

import io.pinoRAG.ingest.embed.EmbedderSelector;
import io.pinoRAG.llm.LlmClient;
import io.pinoRAG.llm.LlmClientSelector;
import io.pinoRAG.llm.LlmRequest;
import io.pinoRAG.llm.TokenConsumer;
import io.pinoRAG.retrieval.ContextAssembler;
import io.pinoRAG.retrieval.ScoredChunk;
import io.pinoRAG.retrieval.VectorRetriever;
import io.pinoRAG.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

// The query orchestrator. The controller hands us an SseEmitter; we own
// the emit lifecycle from here. Status fires first so callers see bytes
// before we touch the embedder. Citations land before the first token so
// the UI can render sources during generation. Query log writes happen
// AFTER the stream completes so a failed write does not abort the answer.
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final EmbedderSelector embedders;
    private final LlmClientSelector llms;
    private final VectorRetriever retriever;
    private final ContextAssembler assembler;
    private final PromptTemplate template;
    private final QueryProperties props;
    private final TenantContext tenant;
    private final JdbcTemplate jdbc;

    public QueryService(EmbedderSelector embedders,
                        LlmClientSelector llms,
                        VectorRetriever retriever,
                        ContextAssembler assembler,
                        PromptTemplate template,
                        QueryProperties props,
                        TenantContext tenant,
                        JdbcTemplate jdbc) {
        this.embedders = embedders;
        this.llms = llms;
        this.retriever = retriever;
        this.assembler = assembler;
        this.template = template;
        this.props = props;
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    public void run(QueryRequest request, SseEmitter emitter) {
        Long tenantId = tenant.requireTenantId();
        Long apiKeyId = tenant.apiKeyId();
        long startedAt = System.currentTimeMillis();

        try {
            emit(emitter, "status", new SseEventPayloads.Status("retrieving"));

            int k = request.k() == null ? props.topK() : request.k();
            double minScore = request.minScore() == null ? props.minScore() : request.minScore();

            float[] queryVector = embedders.active().embed(List.of(request.question())).get(0);
            List<ScoredChunk> ranked = retriever.search(
                    tenantId, request.collectionId(), queryVector, k);

            List<ScoredChunk> passing = ranked.stream()
                    .filter(c -> c.score() >= minScore)
                    .toList();

            if (passing.isEmpty()) {
                runFallback(emitter, request);
                logQuery(tenantId, apiKeyId, request.question(), List.of(),
                        System.currentTimeMillis() - startedAt);
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
            case REFUSE -> {
                emit(emitter, "token", new SseEventPayloads.Token(
                        "I do not have any relevant context to answer that."));
            }
            case MESSAGE -> {
                emit(emitter, "token", new SseEventPayloads.Token(props.fallbackMessage()));
            }
            case LLM_ONLY -> {
                LlmRequest noCtx = new LlmRequest(template.system(),
                        template.user("", req.question()),
                        props.maxAnswerTokens(), props.temperature());
                CountdownConsumer cc = new CountdownConsumer(emitter);
                llms.active().stream(noCtx, cc);
                cc.awaitTerminal();
            }
        }
        emit(emitter, "done", new SseEventPayloads.Done(0L, List.of()));
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

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }

    // Lightweight latch around a TokenConsumer so the LLM_ONLY fallback can
    // wait for streaming to finish before we emit the trailing done event.
    private final class CountdownConsumer implements TokenConsumer {
        private final SseEmitter emitter;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        CountdownConsumer(SseEmitter emitter) { this.emitter = emitter; }
        @Override public void onToken(String text) {
            emit(emitter, "token", new SseEventPayloads.Token(text));
        }
        @Override public void onComplete() { latch.countDown(); }
        @Override public void onError(Throwable t) { latch.countDown(); }
        void awaitTerminal() {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
