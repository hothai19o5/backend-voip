package com.x.backendvoip.calling;

import jakarta.validation.constraints.*;

public record CallWrite(
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}\\s]+") String id,
        @NotBlank @Size(max = 320) String peer,
        @Size(max = 255) String name,
        @NotNull Direction direction,
        @NotNull Status status,
        @NotNull Boolean video,
        @NotNull @Min(1) @Max(MAX_TIME) Long startedAt,
        @Min(1) @Max(MAX_TIME) Long answeredAt,
        @NotNull @Min(1) @Max(MAX_TIME) Long endedAt
) {
    static final long MAX_TIME = 8640000000000000L;

    public enum Direction { INCOMING, OUTGOING }

    public enum Status {
        COMPLETED, MISSED, DECLINED, CANCELED, BUSY, UNANSWERED, FAILED, INTERRUPTED, ANSWERED_ELSEWHERE
    }
}
