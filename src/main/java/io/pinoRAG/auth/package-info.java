// Authentication and authorization.
//
// Two filter strategies: X-API-Key (prefix + sha256 secret) and
// Authorization: Bearer JWT (HS256). Both end at the same outcome -
// SecurityContext holds a TenantAuthentication, TenantContext is populated
// for the request, and downstream code reads tenantId from there.
//
// Owns: pino_api_keys entity + repository. API key creation utilities.
package io.pinoRAG.auth;
