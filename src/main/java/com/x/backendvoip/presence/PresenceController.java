package com.x.backendvoip.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/presence")
@RequiredArgsConstructor
public class PresenceController {
    private final PresenceService presence;

    public record Snapshot(Set<UUID> onlineUserIds) {}

    @PostMapping("/heartbeat")
    public Snapshot heartbeat(@AuthenticationPrincipal Jwt token) {
        return new Snapshot(presence.heartbeat(UUID.fromString(token.getId()), UUID.fromString(token.getSubject())));
    }
}
