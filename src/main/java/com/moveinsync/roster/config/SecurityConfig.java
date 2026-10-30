package com.moveinsync.roster.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.config.CorrelationIdFilter;
import com.moveinsync.roster.exception.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${roster.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("ROSTER_JWT_SECRET must contain at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper) throws Exception {
        CorrelationIdFilter correlationIdFilter = new CorrelationIdFilter();
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(),
                    new ApiError("UNAUTHENTICATED", "Authentication is required.",
                            request.getHeader(CorrelationIdFilter.HEADER), null));
        };
        AccessDeniedHandler forbidden = (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(),
                    new ApiError("FORBIDDEN", "The requested operation is not allowed.",
                            request.getHeader(CorrelationIdFilter.HEADER), null));
        };
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/hr/events", "/api/v1/hr/snapshots")
                        .hasRole("HR_SOURCE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/hr/snapshots/*",
                                "/api/v1/hr/snapshots/*/errors")
                        .hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/hr/snapshots/*/approve",
                                "/api/v1/hr/snapshots/*/reject").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/employees/*/transport-preferences")
                        .hasRole("TRANSPORT_ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("TRANSPORT_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/employees/**")
                        .hasAnyRole("EMPLOYEE", "TRANSPORT_ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterBefore(correlationIdFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
