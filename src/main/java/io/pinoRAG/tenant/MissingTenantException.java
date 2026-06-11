package io.pinoRAG.tenant;

public class MissingTenantException extends RuntimeException {
    public MissingTenantException(String operation) {
        super("Tenant id is required for operation: " + operation);
    }
}
