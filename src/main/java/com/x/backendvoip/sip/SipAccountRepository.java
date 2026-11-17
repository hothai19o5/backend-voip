package com.x.backendvoip.sip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SipAccountRepository extends JpaRepository<SipAccount, UUID> {
    Optional<SipAccount> findByUserId(UUID userId);
    @Query("select a.extension from SipAccount a where a.domainId = :domainId")
    List<String> reservedExtensions(@Param("domainId") UUID domainId);
    @Query("select a from SipAccount a join fetch a.user where a.user.id in :userIds")
    List<SipAccount> forUsers(@Param("userIds") Collection<UUID> userIds);
}
