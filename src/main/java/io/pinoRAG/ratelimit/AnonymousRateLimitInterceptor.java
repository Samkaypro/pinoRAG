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

// Bucket per client IP for requests that have no resolved tenant yet.
// Stops anonymous floods from cycling auth + chain at line rate even though
// they all end in 401. The authenticated path is gated by the per-key bucket
// in RateLimitInterceptor.
@Component
public class AnonymousRateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bandwidth bandwidth;
    private final ObjectProvider<TenantContext> tenantContextProvider;

    public AnonymousRateLimitInterceptor(RateLimitProperties props,
                                         ObjectProvider<TenantContext> tenantContextProvider) {
        int rpm = props.anonymousRequestsPerMinute();
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
        TenantContext ctx = tenantContextProvider.getIfAvailable();
        if (ctx != null && ctx.isAuthenticated()) {
            return true;
        }
        String key = clientIp(request);
        Bucket bucket = buckets.computeIfAbsent(key,
                ip -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfter = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        return false;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
