// Observability.
//
// Custom HealthIndicators, Micrometer metrics registration, and (in later
// phases) OpenTelemetry tracing wiring. Feature code emits metrics via the
// MeterRegistry directly; this package owns the configuration only.
package io.pinoRAG.observability;
