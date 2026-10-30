package com.moveinsync.roster.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record CurrentActor(String subject, String tenantId, String employeeId, String sourceSystem) {
    public static CurrentActor from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("A verified JWT is required");
        }

        var claims = token.getToken().getClaims();
        return new CurrentActor(
                token.getName(),
                (String) claims.get("tenant_id"),
                (String) claims.get("employee_id"),
                (String) claims.get("source_system"));
    }
}
