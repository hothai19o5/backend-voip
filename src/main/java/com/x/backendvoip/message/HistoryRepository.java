package com.x.backendvoip.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HistoryRepository extends JpaRepository<HistoryMessage, Long> {
    Optional<HistoryMessage> findByOwnerIdAndMessageId(UUID ownerId, String messageId);

    @Query("select m from HistoryMessage m where m.owner.id = :owner and m.peer = :peer " +
            "and m.sequence < :before order by m.sequence desc")
    List<HistoryMessage> messages(UUID owner, String peer, long before, Pageable page);

    @Query("select m from HistoryMessage m where m.owner.id = :owner " +
            "and m.sequence > :after order by m.sequence asc")
    List<HistoryMessage> updates(UUID owner, long after, Pageable page);

    @Query("select m from HistoryMessage m where m.owner.id = :owner and m.sequence < :before " +
            "and m.sequence = (select max(n.sequence) from HistoryMessage n " +
            "where n.owner.id = :owner and n.peer = m.peer) order by m.sequence desc")
    List<HistoryMessage> conversations(UUID owner, long before, Pageable page);
}
