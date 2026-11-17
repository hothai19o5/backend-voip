package com.x.backendvoip.sip;

import com.x.backendvoip.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sip_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sip_accounts_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_sip_accounts_extension", columnNames = {"domain_uuid", "extension"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SipAccount {
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "domain_uuid", nullable = false)
    private UUID domainId;

    @Column(name = "domain_name", nullable = false)
    private String domainName;

    @Column(nullable = false, length = 16)
    private String extension;

    @Column(name = "extension_uuid")
    private UUID extensionId;

    @Column(name = "encrypted_password", length = 2048)
    private String encryptedPassword;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", nullable = false, length = 16)
    private ProvisioningStatus provisioningStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SipAccount(AppUser user, UUID domainId, String domainName, String extension, Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.domainId = domainId;
        this.domainName = domainName;
        this.extension = extension;
        this.provisioningStatus = ProvisioningStatus.PROVISIONING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(UUID extensionId, String encryptedPassword, Instant now) {
        this.extensionId = extensionId;
        this.encryptedPassword = encryptedPassword;
        this.provisioningStatus = ProvisioningStatus.READY;
        this.updatedAt = now;
    }

    public void fail(ProvisioningStatus status, Instant now) {
        this.provisioningStatus = status;
        this.updatedAt = now;
    }

    public SipSummary summary(Boolean extensionPresent) {
        return new SipSummary(extension, domainName, provisioningStatus, extensionPresent);
    }
}
