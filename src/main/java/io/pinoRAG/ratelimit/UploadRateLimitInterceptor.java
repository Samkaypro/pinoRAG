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

// Tighter bucket for document upload. Bound to POST
// /v1/collections/*/documents only. Keyed by API key id. Defaults to a
// small fraction of the generic per-key limit because uploads are
// expensive: storage write, parse, chunk, embed, persist.
@Component
public class UploadRateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;
    private final ObjectProvider<TenantContext> tenantContextProvider;

    public UploadRateLimitInterceptor(RateLimitProperties props,
                                      ObjectProvider<TenantContext> tenantContextProvider) {
        int rpm = props.uploadRequestsPerMinute();
        this.bandwidth = Bandwidth.builder()
                .capacity(rpm)
                .refillGreedy(rpm, Duration.ofMinutes(1))
                .build();
        this.tenantContextProvider = tenantContextProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        TenantContext ctx = tenantContextProvider.getIfAvailable();
        if (ctx == null || ctx.apiKeyId() == null) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(ctx.apiKeyId(),
                id -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfter = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        return false;
    }
}
