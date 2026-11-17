package com.x.backendvoip.auth;

import com.x.backendvoip.user.UserProfile;
import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserProfile user) {}
