package com.x.backendvoip.calling;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface CallRepository extends JpaRepository<CallRecord, Long> {
    interface MissedSummary {
        String getPeer();
        long getUnread();
        Long getThrough();
    }

    Optional<CallRecord> findByOwnerIdAndDirectionAndCallId(UUID owner, CallWrite.Direction direction, String id);

    @Query("""
            select c from CallRecord c where c.owner.id = :owner and (:missedOnly = false or c.status = :missedStatus)
              and (c.startedAt < :time or (c.startedAt = :time and c.sequence < :sequence))
            order by c.startedAt desc, c.sequence desc
            """)
    List<CallRecord> page(UUID owner, boolean missedOnly, CallWrite.Status missedStatus,
                          long time, long sequence, Pageable limit);

    @Query("""
            select c from CallRecord c where c.owner.id = :owner and c.peer = :peer
              and (:missedOnly = false or c.status = :missedStatus)
              and (c.endedAt < :time or (c.endedAt = :time and c.sequence < :sequence))
            order by c.endedAt desc, c.sequence desc
            """)
    List<CallRecord> thread(UUID owner, String peer, boolean missedOnly, CallWrite.Status missedStatus,
                            long time, long sequence, Pageable limit);

    @Query("""
            select c from CallRecord c where c.owner.id = :owner
              and (c.endedAt < :time or (c.endedAt = :time and c.sequence < :sequence))
              and not exists (select 1 from CallRecord n where n.owner.id = :owner and n.peer = c.peer
                and (n.endedAt > c.endedAt or (n.endedAt = c.endedAt and n.sequence > c.sequence)))
            order by c.endedAt desc, c.sequence desc
            """)
    List<CallRecord> threads(UUID owner, long time, long sequence, Pageable limit);

    @Query("""
            select c.peer as peer, count(c) as unread, max(c.sequence) as through from CallRecord c
            where c.owner.id = :owner and c.peer in :peers and c.status = :status and c.seenAt is null
            group by c.peer
            """)
    List<MissedSummary> missedByPeer(UUID owner, Collection<String> peers, CallWrite.Status status);

    @Query("""
            select count(c) as unread, max(c.sequence) as through from CallRecord c
            where c.owner.id = :owner and c.status = :status and c.seenAt is null
            """)
    MissedSummary missed(UUID owner, CallWrite.Status status);

    @Modifying
    @Query("""
            update CallRecord c set c.seenAt = :now where c.owner.id = :owner and c.status = :status
            and c.seenAt is null and c.sequence <= :through and (:allPeers = true or c.peer = :peer)
            """)
    void markSeen(UUID owner, CallWrite.Status status, long through, long now, boolean allPeers, String peer);
}
