package com.x.backendvoip.auth;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,32}", message = "must be 3–32 letters, digits, dots, underscores or hyphens") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(max = 40) String phoneNumber) {
    public RegisterRequest {
        username = username == null ? null : username.strip();
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }
}
