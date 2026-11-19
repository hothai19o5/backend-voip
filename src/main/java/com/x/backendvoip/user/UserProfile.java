package com.x.backendvoip.user;

import com.x.backendvoip.sip.SipSummary;

import java.time.Instant;
import java.util.UUID;

public record UserProfile(UUID id, String username, String email, String displayName,
                          UserRole role, UserStatus status, Instant createdAt, SipSummary sip,
                          String phoneNumber, boolean phoneCallingEnabled) {
    public static UserProfile from(AppUser user, SipSummary sip, boolean phoneCallingEnabled) {
        return new UserProfile(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.getStatus(), user.getCreatedAt(), sip, user.getPhoneE164(), phoneCallingEnabled);
    }
}
