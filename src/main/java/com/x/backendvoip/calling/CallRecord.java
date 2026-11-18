package com.x.backendvoip.calling;

import com.x.backendvoip.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "call_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_call_owner_direction_id", columnNames = {"owner_id", "direction", "call_id"}), indexes = {
        @Index(name = "idx_call_owner_started", columnList = "owner_id,started_at,sequence"),
        @Index(name = "idx_call_owner_missed", columnList = "owner_id,status,seen_at,sequence"),
        @Index(name = "idx_call_owner_peer_ended", columnList = "owner_id,peer,ended_at,sequence")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CallRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;
    @Column(name = "call_id", nullable = false, length = 255)
    private String callId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CallWrite.Direction direction;
    @Column(nullable = false, length = 320)
    private String peer;
    @Column(length = 255)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CallWrite.Status status;
    @Column(nullable = false)
    private boolean video;
    @Column(name = "started_at", nullable = false)
    private long startedAt;
    private Long answeredAt;
    @Column(name = "ended_at", nullable = false)
    private long endedAt;
    private Long seenAt;

    CallRecord(AppUser owner, String peer, CallWrite input) {
        this.owner = owner;
        this.callId = input.id();
        this.direction = input.direction();
        this.peer = peer;
        apply(input);
    }

    void merge(CallWrite input) {
        // Multiple contacts can report the same incoming INVITE. A confirmed
        // answer or deliberate decline takes precedence over a ringing leg.
        if (direction == CallWrite.Direction.INCOMING
                && evidence(input.status(), input.answeredAt()) > evidence(status, answeredAt)) apply(input);
    }

    private static int evidence(CallWrite.Status status, Long answeredAt) {
        if (answeredAt != null) return 3;
        return switch (status) {
            case ANSWERED_ELSEWHERE -> 2;
            case DECLINED -> 1;
            default -> 0;
        };
    }

    private void apply(CallWrite input) {
        this.name = input.name() == null || input.name().isBlank() ? null : input.name().strip();
        this.status = input.status();
        this.video = input.video();
        this.startedAt = input.startedAt();
        this.answeredAt = input.answeredAt();
        this.endedAt = input.endedAt();
    }
}
