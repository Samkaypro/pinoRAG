// Rate limiting.
//
// Two Bucket4j buckets registered as HandlerInterceptors:
//   AnonymousRateLimitInterceptor   - per-IP bucket for unauthenticated traffic
//   RateLimitInterceptor            - per-API-key bucket for authenticated traffic
//
// The anonymous interceptor fires first so a flood of bad credentials gets
// stopped before it touches the auth filter chain.
package io.pinoRAG.ratelimit;
