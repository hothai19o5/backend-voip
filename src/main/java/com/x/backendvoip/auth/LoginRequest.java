package com.x.backendvoip.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 32) String username,
                           @NotBlank @Size(max = 128) String password) {
    public LoginRequest {
        username = username == null ? null : username.strip();
    }
}
