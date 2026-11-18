package com.x.backendvoip.user;

import com.x.backendvoip.sip.SipSummary;

import java.util.UUID;

public record UserSummary(UUID id, String username, String displayName, UserStatus status, SipSummary sip, boolean online) {
    public static UserSummary from(AppUser user, SipSummary sip, boolean online) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getStatus(), sip, online);
    }
}
