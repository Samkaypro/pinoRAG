package io.pinoRAG.query;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.pinoRAG.ingest.embed.EmbedderSelector;
import io.pinoRAG.llm.LlmClientSelector;
import io.pinoRAG.llm.LlmRequest;
import io.pinoRAG.llm.TokenConsumer;
import io.pinoRAG.observability.QueryMetrics;
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
    private final QueryMetrics metrics;
    private final ObservationRegistry observations;

    public QueryService(EmbedderSelector embedders,
                        LlmClientSelector llms,
                        Retrievers retrievers,
                        ContextAssembler assembler,
                        PromptTemplate template,
                        QueryProperties props,
                        JdbcTemplate jdbc,
                        QueryMetrics metrics,
                        ObservationRegistry observations) {
        this.embedders = embedders;
        this.llms = llms;
        this.retrievers = retrievers;
        this.assembler = assembler;
        this.template = template;
        this.props = props;
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.observations = observations;
    }

    public void run(QueryRequest request, Long tenantId, Long apiKeyId,
                    String subject, String[] groups, SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        RetrievalMode mode = request.mode() == null ? props.retrievalMode() : request.mode();

        Observation observation = Observation.createNotStarted("pinorag.query", observations)
                .lowCardinalityKeyValue("mode", mode.name())
                .start();

        try (Observation.Scope ignored = observation.openScope()) {
            // Emit the first event ASAP and record TTFT BEFORE doing any
            // upstream work. This is the metric the dashboard tracks.
            emit(emitter, "status", new SseEventPayloads.Status("retrieving"));
            metrics.recordTtft(System.currentTimeMillis() - startedAt, mode.name());

            int k = request.k() == null ? props.topK() : request.k();
            double minScore = request.minScore() == null ? props.minScore() : request.minScore();

            // Embed only when the chosen mode actually needs a vector.
            // BM25 reads from the text field; computing the embedding for
            // that path would be a wasted round-trip to the embedder.
            float[] queryVector = needsEmbedding(mode)
                    ? Observation.createNotStarted("pinorag.embed", observations)
                            .observe(() -> embedders.active().embed(List.of(request.question())).get(0))
                    : null;
            RetrievalQuery retrievalQuery = new RetrievalQuery(
                    request.question(), queryVector, subject, groups);

            long retrieveStart = System.currentTimeMillis();
            List<ScoredChunk> ranked = Observation.createNotStarted("pinorag.retrieve", observations)
                    .lowCardinalityKeyValue("mode", mode.name())
                    .observe(() -> retrievers.forMode(mode).search(
                            tenantId, request.collectionId(), retrievalQuery, k));
            metrics.recordRetrievalLatency(System.currentTimeMillis() - retrieveStart, mode.name());

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
                metrics.countQuery(mode.name(), "fallback");
                metrics.recordQueryLatency(System.currentTimeMillis() - startedAt,
                        mode.name(), "fallback");
                observation.lowCardinalityKeyValue("outcome", "fallback").stop();
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
            streamTokensObserved(emitter, llmReq, startedAt, usedIds);
            logQuery(tenantId, apiKeyId, request.question(), usedIds,
                    System.currentTimeMillis() - startedAt);
            metrics.countQuery(mode.name(), "success");
            metrics.recordQueryLatency(System.currentTimeMillis() - startedAt,
                    mode.name(), "success");
            observation.lowCardinalityKeyValue("outcome", "success").stop();
        } catch (RuntimeException ex) {
            log.error("Query failed", ex);
            tryEmitError(emitter, "INTERNAL", "Query failed");
            emitter.completeWithError(ex);
            metrics.countQuery(mode.name(), "error");
            metrics.recordQueryLatency(System.currentTimeMillis() - startedAt,
                    mode.name(), "error");
            observation.lowCardinalityKeyValue("outcome", "error").error(ex).stop();
        }
    }

    private void streamTokensObserved(SseEmitter emitter, LlmRequest req,
                                      long startedAt, List<Long> usedIds) {
        Observation llmObs = Observation.createNotStarted("pinorag.llm.stream", observations).start();
        try (Observation.Scope ignored = llmObs.openScope()) {
            streamTokens(emitter, req, startedAt, usedIds);
        } finally {
            llmObs.stop();
        }
    }

    private void streamTokens(SseEmitter emitter, LlmRequest req,
                              long startedAt, List<Long> usedIds) {
        // Block the calling virtual thread until the LLM finishes. The
        // FakeLlmClient runs synchronously and counts down before stream()
        // returns; the OllamaLlmClient (Spring AI Flux) is fire-and-forget
        // and finishes much later on a Reactor scheduler thread. Without
        // this latch the outer metrics + query log fire BEFORE the LLM
        // has actually finished, and the observation span closes while
        // tokens are still being emitted.
        CountDownLatch done = new CountDownLatch(1);
        llms.active().stream(req, new TokenConsumer() {
            @Override public void onToken(String text) {
                emit(emitter, "token", new SseEventPayloads.Token(text));
            }
            @Override public void onComplete() {
                emit(emitter, "done", new SseEventPayloads.Done(
                        System.currentTimeMillis() - startedAt, usedIds));
                emitter.complete();
                done.countDown();
            }
            @Override public void onError(Throwable t) {
                log.error("LLM stream failed", t);
                tryEmitError(emitter, "LLM_FAILED", "LLM stream failed");
                emitter.completeWithError(t);
                done.countDown();
            }
        });
        try {
            if (!done.await(props.llmTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("LLM stream exceeded {} ms", props.llmTimeoutMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
