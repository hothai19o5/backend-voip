package com.x.backendvoip.message;

import jakarta.validation.constraints.*;

public record HistoryWrite(
        @NotBlank @Size(max = 320) String account,
        @NotBlank @Size(max = 320) String peer,
        @NotBlank @Size(max = 255) String name,
        @NotNull @Pattern(regexp = "incoming|outgoing") String direction,
        @NotNull @Size(min = 1, max = 65535) String body,
        @NotNull @Pattern(regexp = "pending|accepted|failed|received") String status,
        @NotNull @Min(1) @Max(8640000000000000L) Long time,
        @Size(max = 255) String senderName
) {}
