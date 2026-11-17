package com.x.backendvoip.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("app.auth")
public record AuthProperties(String issuer, Duration tokenTtl, String jwtSecret) {
    public AuthProperties {
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("app.auth.issuer is required");
        if (tokenTtl == null || tokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("app.auth.token-ttl must be at least one second");
        }
    }
}
