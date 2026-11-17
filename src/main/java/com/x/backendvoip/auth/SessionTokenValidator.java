package com.x.backendvoip.auth;

import com.x.backendvoip.user.UserRole;
import com.x.backendvoip.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final AuthSessionRepository sessions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Instant now = clock.instant();
            boolean valid = token.getExpiresAt() != null && token.getExpiresAt().isAfter(now)
                    && token.getAudience().contains("alohub-api")
                    && sessions.isActive(UUID.fromString(token.getId()), UUID.fromString(token.getSubject()),
                        now, UserStatus.ACTIVE, UserRole.valueOf(token.getClaimAsString("role")));
            if (valid) return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Malformed identity/session claims are authentication failures.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session is not active.", null));
    }
}
