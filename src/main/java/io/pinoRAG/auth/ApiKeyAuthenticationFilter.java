package io.pinoRAG.auth;

import io.pinoRAG.domain.entity.ApiKeyEntity;
import io.pinoRAG.domain.repository.ApiKeyRepository;
import io.pinoRAG.tenant.AuthPrincipalKind;
import io.pinoRAG.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeys;
    private final ApiKeyHasher hasher;
    private final ObjectProvider<TenantContext> tenantContextProvider;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys,
                                      ApiKeyHasher hasher,
                                      ObjectProvider<TenantContext> tenantContextProvider) {
        this.apiKeys = apiKeys;
        this.hasher = hasher;
        this.tenantContextProvider = tenantContextProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        ApiKeyMaterial material = ApiKeyMaterial.parse(token);
        if (material == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiKeyEntity> match = apiKeys.findByPrefixUnscoped(material.prefix());
        if (match.isEmpty() || !hasher.matches(material.secret(), match.get().getHashedSecret())) {
            chain.doFilter(request, response);
            return;
        }

        ApiKeyEntity key = match.get();
        TenantAuthentication auth = new TenantAuthentication(
                key.getTenantId(),
                key.getId(),
                AuthPrincipalKind.API_KEY,
                key.getScopes());
        SecurityContextHolder.getContext().setAuthentication(auth);

        TenantContext ctx = tenantContextProvider.getIfAvailable();
        if (ctx != null) {
            ctx.set(key.getTenantId(), key.getId(), AuthPrincipalKind.API_KEY, key.getScopes());
        }

        // Best-effort. A failure here must not block the request.
        try {
            apiKeys.touchLastUsed(key.getId(), OffsetDateTime.now());
        } catch (Exception ignored) {
        }

        chain.doFilter(request, response);
    }
}
