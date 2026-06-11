package io.pinoRAG.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.pinoRAG.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// In-memory token-bucket per API key id. Per process, not cluster-wide.
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;
    private final ObjectProvider<TenantContext> tenantContextProvider;

    public RateLimitInterceptor(RateLimitProperties props,
                                ObjectProvider<TenantContext> tenantContextProvider) {
        int rpm = props.requestsPerMinute();
        int burst = Math.max(props.burst(), rpm);
        this.bandwidth = Bandwidth.builder()
                .capacity(burst)
                .refillGreedy(rpm, Duration.ofMinutes(1))
                .build();
        this.tenantContextProvider = tenantContextProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        TenantContext ctx = tenantContextProvider.getIfAvailable();
        if (ctx == null || !ctx.isAuthenticated() || ctx.apiKeyId() == null) {
            // Either anonymous (will fail auth) or JWT (no API key id to key on).
            return true;
        }

        Bucket bucket = buckets.computeIfAbsent(ctx.apiKeyId(),
                id -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Remaining", "0");
        return false;
    }
}
