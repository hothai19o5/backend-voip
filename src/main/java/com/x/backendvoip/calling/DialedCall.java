package com.x.backendvoip.calling;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "dialed_calls", indexes = @Index(name = "idx_dialed_calls_owner_started", columnList = "owner_id,started_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class DialedCall {
    enum Route { APP_TO_APP, APP_TO_PHONE }

    @Id
    // Retain the existing column so previously imported PBX records stay readable.
    @Column(name = "a_leg_uuid")
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "phone_e164", nullable = false, length = 16)
    private String phoneE164;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Route route;

    @Column(length = 320)
    private String sipPeer;
    private String destinationName;

    @Column(name = "started_at", nullable = false)
    private long startedAt;

    private Long answeredAt;
    private Long endedAt;

    @Enumerated(EnumType.STRING)
    private CallWrite.Status status;

    DialedCall(UUID id, UUID ownerId, String phoneE164, String sipPeer, Route route, CallWrite input) {
        this.id = id;
        this.ownerId = ownerId;
        this.phoneE164 = phoneE164;
        this.sipPeer = sipPeer;
        this.route = route;
        this.destinationName = input.name() == null || input.name().isBlank() ? null : input.name().strip();
        this.startedAt = input.startedAt();
        this.answeredAt = input.answeredAt();
        this.endedAt = input.endedAt();
        this.status = input.status();
    }
}
