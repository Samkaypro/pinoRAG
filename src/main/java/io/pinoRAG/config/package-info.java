// Cross-cutting @Configuration beans.
//
// Anything that touches more than one feature and is not a feature in its
// own right goes here. SecurityConfig stays in auth/ because it is tightly
// bound to the auth filters. RateLimitWebConfig stays in ratelimit/ for
// the same reason.
package io.pinoRAG.config;
