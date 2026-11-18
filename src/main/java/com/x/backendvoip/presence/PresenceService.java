package com.x.backendvoip.presence;

import com.x.backendvoip.auth.AuthSessionRepository;
import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PresenceService {
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(75);

    private final AuthSessionRepository sessions;
    private final Clock clock;

    @Transactional
    public Set<UUID> heartbeat(UUID sessionId, UUID userId) {
        var now = clock.instant();
        if (sessions.renewPresence(sessionId, userId, now, now.plus(PRESENCE_TTL)) == 0)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Session is not active.");
        return onlineUserIds();
    }

    @Transactional(readOnly = true)
    public Set<UUID> onlineUserIds() {
        return sessions.onlineUserIds(clock.instant(), UserStatus.ACTIVE);
    }
}
