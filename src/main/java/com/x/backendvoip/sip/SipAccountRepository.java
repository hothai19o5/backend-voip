package com.x.backendvoip.sip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SipAccountRepository extends JpaRepository<SipAccount, UUID> {
    interface PeerMapping {
        String getPeer();
        UUID getUserId();
        ProvisioningStatus getProvisioningStatus();
    }

    Optional<SipAccount> findByUserId(UUID userId);
    List<SipAccount> findFirst2ByDomainNameIgnoreCaseAndExtension(String domainName, String extension);
    @Query("select a.extension from SipAccount a where a.domainId = :domainId")
    List<String> reservedExtensions(@Param("domainId") UUID domainId);
    @Query("select a from SipAccount a join fetch a.user where a.user.id in :userIds")
    List<SipAccount> forUsers(@Param("userIds") Collection<UUID> userIds);

    @Query("""
            select concat('sip:', a.extension, '@', lower(a.domainName)) as peer,
                   a.user.id as userId, a.provisioningStatus as provisioningStatus
            from SipAccount a where concat('sip:', a.extension, '@', lower(a.domainName)) in :peers
            """)
    List<PeerMapping> forPeers(Collection<String> peers);
}
