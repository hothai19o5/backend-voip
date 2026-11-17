package com.x.backendvoip.auth;

import com.x.backendvoip.user.UserRole;
import com.x.backendvoip.user.UserStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    @Query("""
            select count(s) > 0 from AuthSession s join s.user u
            where s.id = :id and u.id = :userId and s.revokedAt is null
              and s.expiresAt > :now and u.status = :status and u.role = :role
            """)
    boolean isActive(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now,
                     @Param("status") UserStatus status, @Param("role") UserRole role);

    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.id = :id and s.user.id = :userId and s.revokedAt is null")
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);
}
