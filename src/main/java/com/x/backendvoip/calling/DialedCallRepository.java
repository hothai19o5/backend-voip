package com.x.backendvoip.calling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface DialedCallRepository extends JpaRepository<DialedCall, UUID> {
    List<DialedCall> findTop30ByOwnerIdAndEndedAtIsNotNullOrderByEndedAtDescIdDesc(UUID ownerId);
}
