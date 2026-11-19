package com.x.backendvoip.calling;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.sip.SipAddress;
import com.x.backendvoip.user.AppUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.x.backendvoip.calling.CallWrite.Status.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CallService {
    private final CallRepository calls;
    private final PhoneCallService phones;
    private final EntityManager entities;
    private final Clock clock;

    public record Entry(String id, String sequence, String peer, String name, CallWrite.Direction direction,
                        CallWrite.Status status, boolean video, long startedAt, Long answeredAt, long endedAt,
                        boolean unread) {
        static Entry from(CallRecord call) {
            return new Entry(call.getCallId(), call.getSequence().toString(), call.getPeer(), call.getName(), call.getDirection(),
                    call.getStatus(), call.isVideo(), call.getStartedAt(), call.getAnsweredAt(), call.getEndedAt(),
                    call.getStatus() == MISSED && call.getSeenAt() == null);
        }
    }

    public record Page<T>(List<T> content, String nextCursor) {}
    public record Missed(long unread, String through) {}
    public record Thread(Entry lastCall, Missed missed) {}
    private record Cursor(long time, long sequence) {}

    @Transactional
    public void save(UUID ownerId, CallWrite input) {
        validate(input);
        String peer = SipAddress.normalize(input.peer());
        if (peer.chars().anyMatch(Character::isISOControl) || peer.indexOf('@') != peer.lastIndexOf('@'))
            throw invalid("Invalid SIP address.");
        var owner = lockOwner(ownerId);
        if (input.dialedPhoneNumber() != null
                && phones.recordDialed(ownerId, peer, input) == DialedCall.Route.APP_TO_PHONE) return;
        var existing = calls.findByOwnerIdAndDirectionAndCallId(ownerId, input.direction(), input.id()).orElse(null);
        if (existing == null) {
            calls.save(new CallRecord(owner, peer, input));
        } else {
            if (!existing.getPeer().equals(peer))
                throw new ApiException(HttpStatus.CONFLICT, "CALL_CONFLICT", "This call id belongs to another peer.");
            existing.merge(input);
        }
    }

    public Page<Entry> page(UUID owner, boolean missedOnly, String before, int size, String peer) {
        Cursor cursor = cursor(before, size);
        var limit = PageRequest.of(0, size + 1);
        var rows = peer == null
                ? calls.page(owner, missedOnly, MISSED, cursor.time(), cursor.sequence(), limit)
                : calls.thread(owner, SipAddress.normalize(peer), missedOnly, MISSED, cursor.time(), cursor.sequence(), limit);
        return new Page<>(rows.stream().limit(size).map(Entry::from).toList(), next(rows, size, peer != null));
    }

    public Page<Thread> threads(UUID owner, String before, int size) {
        Cursor cursor = cursor(before, size);
        var rows = calls.threads(owner, cursor.time(), cursor.sequence(), PageRequest.of(0, size + 1));
        var visible = rows.subList(0, Math.min(size, rows.size()));
        if (visible.isEmpty()) return new Page<>(List.of(), null);
        var peers = visible.stream().map(CallRecord::getPeer).toList();
        var missed = calls.missedByPeer(owner, peers, MISSED).stream()
                .collect(Collectors.toMap(CallRepository.MissedSummary::getPeer, Function.identity()));
        var threads = visible.stream().map(row -> new Thread(Entry.from(row), missed(missed.get(row.getPeer())))).toList();
        return new Page<>(threads, next(rows, size, true));
    }

    private Cursor cursor(String before, int size) {
        if (size < 1 || size > 100) throw invalid("size must be 1–100.");
        if (before == null) return new Cursor(Long.MAX_VALUE, Long.MAX_VALUE);
        String[] parts = before.split(":", -1);
        if (parts.length != 2) throw invalid("Invalid call history cursor.");
        return new Cursor(positiveLong(parts[0]), positiveLong(parts[1]));
    }

    private String next(List<CallRecord> rows, int size, boolean byEndTime) {
        if (rows.size() <= size) return null;
        var last = rows.get(size - 1);
        return (byEndTime ? last.getEndedAt() : last.getStartedAt()) + ":" + last.getSequence();
    }

    public Missed missed(UUID owner) {
        return missed(calls.missed(owner, MISSED));
    }

    private Missed missed(CallRepository.MissedSummary summary) {
        if (summary == null) return new Missed(0, null);
        Long through = summary.getThrough();
        return new Missed(summary.getUnread(), through == null ? null : through.toString());
    }

    @Transactional
    public Missed markSeen(UUID owner, String through, String peer) {
        long sequence = positiveLong(through);
        String normalized = peer == null ? "" : SipAddress.normalize(peer);
        lockOwner(owner);
        calls.markSeen(owner, MISSED, sequence, clock.millis(), peer == null, normalized);
        return peer == null ? missed(owner)
                : missed(calls.missedByPeer(owner, List.of(normalized), MISSED).stream().findFirst().orElse(null));
    }

    private AppUser lockOwner(UUID id) {
        // Prevent concurrent inserts for the same id and serialize missed-call
        // acknowledgment with sequence allocation, even across backend instances.
        var user = entities.find(AppUser.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
        return user;
    }

    private static long positiveLong(String value) {
        try {
            if (value != null && value.matches("[0-9]{1,19}")) {
                long parsed = Long.parseLong(value);
                if (parsed > 0) return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Invalid cursor or sequence uses the same API error as other invalid values.
        }
        throw invalid("Invalid call history cursor or sequence.");
    }

    private static void validate(CallWrite input) {
        boolean incoming = input.direction() == CallWrite.Direction.INCOMING;
        boolean directionMatches = switch (input.status()) {
            case MISSED, ANSWERED_ELSEWHERE -> incoming;
            case CANCELED, BUSY, UNANSWERED -> !incoming;
            default -> true;
        };
        if (!directionMatches || input.endedAt() < input.startedAt()
                || (input.status() == COMPLETED && input.answeredAt() == null)
                || (input.answeredAt() != null && (input.answeredAt() < input.startedAt()
                    || input.answeredAt() > input.endedAt() || (input.status() != COMPLETED && input.status() != INTERRUPTED)))
                || (input.name() != null && input.name().chars().anyMatch(Character::isISOControl)))
            throw invalid("Invalid call result.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
