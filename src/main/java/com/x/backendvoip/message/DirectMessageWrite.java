package com.x.backendvoip.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DirectMessageWrite(
        @NotBlank @Size(max = 320) String peer,
        @NotBlank @Size(max = 5000) String body
) {}
