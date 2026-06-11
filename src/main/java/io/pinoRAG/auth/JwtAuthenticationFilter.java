package io.pinoRAG.auth;

import io.pinoRAG.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtTokenVerifier verifier;
    private final ObjectProvider<TenantContext> tenantContextProvider;

    public JwtAuthenticationFilter(JwtTokenVerifier verifier,
                                   ObjectProvider<TenantContext> tenantContextProvider) {
        this.verifier = verifier;
        this.tenantContextProvider = tenantContextProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Only skip when the API key filter already produced a tenant auth.
        // The default Spring anonymous token also reports isAuthenticated()
        // as true, so checking the type is what we actually want.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing instanceof TenantAuthentication) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        Optional<JwtTokenVerifier.VerifiedJwt> verified = verifier.verify(token);
        if (verified.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        JwtTokenVerifier.VerifiedJwt jwt = verified.get();
        TenantAuthentication auth = new TenantAuthentication(
                jwt.tenantId(),
                null,
                AuthPrincipalKind.JWT,
                jwt.scopes(),
                jwt.subject(),
                jwt.groups());
        SecurityContextHolder.getContext().setAuthentication(auth);

        TenantContext ctx = tenantContextProvider.getIfAvailable();
        if (ctx != null) {
            ctx.set(jwt.tenantId(), null, AuthPrincipalKind.JWT,
                    jwt.scopes(), jwt.subject(), jwt.groups());
        }

        chain.doFilter(request, response);
    }
}
