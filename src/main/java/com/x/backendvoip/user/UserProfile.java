package com.x.backendvoip.user;

import com.x.backendvoip.sip.SipSummary;

import java.time.Instant;
import java.util.UUID;

public record UserProfile(UUID id, String username, String email, String displayName,
                          UserRole role, UserStatus status, Instant createdAt, SipSummary sip) {
    public static UserProfile from(AppUser user) {
        return from(user, null);
    }

    public static UserProfile from(AppUser user, SipSummary sip) {
        return new UserProfile(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.getStatus(), user.getCreatedAt(), sip);
    }
}
