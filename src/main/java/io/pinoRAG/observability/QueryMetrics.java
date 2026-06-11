package io.pinoRAG.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// Central registry of the query-path metrics. Naming follows
// pinorag.<feature>.<measurement> so prometheus auto-converts to
// pinorag_<feature>_<measurement>_<suffix> per Micrometer convention.
//
// Why a dedicated bean: keeps the metric names and their tags in one
// place, so dashboards reference stable identifiers and so renaming any
// metric breaks at compile time.
@Component
public class QueryMetrics {

    private final MeterRegistry registry;

    public QueryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordTtft(long millis, String mode) {
        Timer.builder("pinorag.query.ttft")
                .description("Time from /v1/query receipt to the first SSE event")
                .tags(Tags.of("mode", mode))
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordQueryLatency(long millis, String mode, String outcome) {
        Timer.builder("pinorag.query.latency")
                .description("Full duration of one /v1/query call, from receipt to done")
                .tags(Tags.of("mode", mode, "outcome", outcome))
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordRetrievalLatency(long millis, String mode) {
        Timer.builder("pinorag.retrieve.latency")
                .description("Duration of a single retriever call, by mode")
                .tags(Tags.of("mode", mode))
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void countQuery(String mode, String outcome) {
        registry.counter("pinorag.query.total",
                Tags.of("mode", mode, "outcome", outcome)).increment();
    }
}
