package io.pinoRAG.tenant;

import io.pinoRAG.auth.AuthPrincipalKind;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

// Holds the verified tenant identity for the current HTTP request.
// Populated by the auth filter chain. Anything that touches per-tenant
// data reads from here. Never accept tenantId from request bodies or
// query params.
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class TenantContext {

    private Long tenantId;
    private Long apiKeyId;
    private AuthPrincipalKind kind;
    private String[] scopes;
    private String subject;
    private String[] groups;

    public void set(Long tenantId, Long apiKeyId, AuthPrincipalKind kind, String[] scopes) {
        set(tenantId, apiKeyId, kind, scopes, null, null);
    }

    public void set(Long tenantId,
                    Long apiKeyId,
                    AuthPrincipalKind kind,
                    String[] scopes,
                    String subject,
                    String[] groups) {
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.kind = kind;
        this.scopes = scopes == null ? new String[0] : scopes;
        this.subject = subject;
        this.groups = groups == null ? new String[0] : groups;
    }

    public Long tenantId() {
        return tenantId;
    }

    public Long apiKeyId() {
        return apiKeyId;
    }

    public AuthPrincipalKind kind() {
        return kind;
    }

    public String[] scopes() {
        return scopes == null ? new String[0] : scopes;
    }

    public String subject() {
        return subject;
    }

    public String[] groups() {
        return groups == null ? new String[0] : groups;
    }

    public boolean isAuthenticated() {
        return tenantId != null && tenantId > 0;
    }

    public Long requireTenantId() {
        if (tenantId == null || tenantId <= 0) {
            throw new MissingTenantException("requireTenantId");
        }
        return tenantId;
    }

    public boolean hasScope(String scope) {
        if (scopes == null) {
            return false;
        }
        for (String s : scopes) {
            if (scope.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
