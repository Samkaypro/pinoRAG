package io.pinoRAG.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class TenantAuthentication extends AbstractAuthenticationToken {

    private final Long tenantId;
    private final Long apiKeyId;
    private final AuthPrincipalKind kind;
    private final String[] scopes;
    private final String subject;
    private final String[] groups;

    public TenantAuthentication(Long tenantId,
                                Long apiKeyId,
                                AuthPrincipalKind kind,
                                String[] scopes,
                                String subject,
                                String[] groups) {
        super(toAuthorities(scopes));
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.kind = kind;
        this.scopes = scopes == null ? new String[0] : scopes;
        this.subject = subject;
        this.groups = groups == null ? new String[0] : groups;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }

    public Long tenantId()   { return tenantId; }
    public Long apiKeyId()   { return apiKeyId; }
    public AuthPrincipalKind kind() { return kind; }
    public String[] scopes() { return Arrays.copyOf(scopes, scopes.length); }
    public String subject()  { return subject; }
    public String[] groups() { return Arrays.copyOf(groups, groups.length); }

    private static List<GrantedAuthority> toAuthorities(String[] scopes) {
        return Stream.of(scopes == null ? new String[0] : scopes)
                .map(s -> "SCOPE_" + s)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}
