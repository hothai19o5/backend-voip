package com.x.backendvoip.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_users_phone_e164", columnNames = "phone_e164")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {
    @Id
    private UUID id;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AppUser(String username, String email, String passwordHash, String displayName, Instant now) {
        this(username, email, passwordHash, displayName, null, now);
    }

    public AppUser(String username, String email, String passwordHash, String displayName, String phoneE164, Instant now) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.phoneE164 = phoneE164;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = UserRole.USER;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void assignPhone(String phoneE164) {
        this.phoneE164 = phoneE164;
    }
}
