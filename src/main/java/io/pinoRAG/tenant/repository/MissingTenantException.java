package io.pinoRAG.tenant.repository;

public class MissingTenantException extends RuntimeException {

    public MissingTenantException(String operation) {
        super("Tenant id is required for operation: " + operation);
    }
}
