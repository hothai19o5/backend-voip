package com.x.backendvoip.message;

import com.x.backendvoip.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "message_history", uniqueConstraints =
        @UniqueConstraint(name = "uk_history_owner_message", columnNames = {"owner_id", "message_id"}),
        indexes = {
                @Index(name = "idx_history_owner_peer_sequence", columnList = "owner_id,peer,sequence"),
                @Index(name = "idx_history_owner_sequence", columnList = "owner_id,sequence")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoryMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(name = "message_id", nullable = false, length = 100)
    private String messageId;
    @Column(nullable = false, length = 320)
    private String account;
    @Column(nullable = false, length = 320)
    private String peer;
    @Column(nullable = false, length = 255)
    private String name;
    @Column(nullable = false, length = 16)
    private String direction;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "message_time", nullable = false)
    private long time;
    @Column(name = "sender_name", length = 255)
    private String senderName;

    HistoryMessage(AppUser owner, String id, String account, String peer, HistoryWrite input) {
        this.owner = owner;
        this.messageId = id;
        this.account = account;
        this.peer = peer;
        this.name = input.name();
        this.direction = input.direction();
        this.body = input.body();
        this.status = input.status();
        this.time = input.time();
        this.senderName = input.senderName();
    }

    void finish(String status) {
        if (this.status.equals("pending")) this.status = status;
    }
}
