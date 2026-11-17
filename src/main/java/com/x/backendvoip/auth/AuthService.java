package com.x.backendvoip.auth;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.user.*;
import com.x.backendvoip.sip.SipProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final RegistrationService registrations;
    private final SipProvisioningService sip;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, AuthSessionRepository sessions, PasswordEncoder passwords,
                       JwtEncoder tokens, AuthProperties properties, Clock clock,
                       RegistrationService registrations, SipProvisioningService sip) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        this.registrations = registrations;
        this.sip = sip;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    public UserProfile register(RegisterRequest request) {
        return registrations.register(request);
    }

    public LoginResponse login(LoginRequest request) {
        var user = users.findByUsername(request.username().toLowerCase(Locale.ROOT)).orElse(null);
        // Always run password hashing, including for unknown usernames.
        boolean passwordMatches = passwords.matches(request.password(), user == null ? dummyPasswordHash : user.getPasswordHash());
        if (!passwordMatches || user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect.");
        }
        var calling = sip.ensureProvisioned(user);
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expiresAt = now.plus(properties.tokenTtl());
        var session = sessions.save(new AuthSession(user, now, expiresAt));
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of("alohub-api"))
                .subject(user.getId().toString()).id(session.getId().toString())
                .issuedAt(now).expiresAt(expiresAt).claim("role", user.getRole().name()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = tokens.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", expiresAt, UserProfile.from(user, calling));
    }

    @Transactional
    public void logout(Jwt token) {
        sessions.revoke(UUID.fromString(token.getId()), UUID.fromString(token.getSubject()), clock.instant());
    }
}
