package com.x.backendvoip.message;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.user.AppUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {
    private final HistoryRepository history;
    private final EntityManager entities;

    public record CursorPage<T>(List<T> content, String nextCursor) {}
    public record Conversation(String peer, String name, HistoryEntry lastMessage) {}

    @Transactional
    public HistoryEntry save(UUID ownerId, String id, HistoryWrite input) {
        if (!id.matches("[A-Za-z0-9_.:-]{1,100}")) throw invalid("Invalid message id.");
        if (input.direction().equals("incoming") != input.status().equals("received"))
            throw invalid("Incoming messages require received status; outgoing messages cannot be received.");
        String peer = normalize(input.peer()), account = normalize(input.account());
        // Serializes concurrent retries for this owner, including first insert. No
        // process-local lock: multiple backend instances obey the same DB lock.
        var owner = entities.find(AppUser.class, ownerId, LockModeType.PESSIMISTIC_WRITE);
        if (owner == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
        var message = history.findByOwnerIdAndMessageId(ownerId, id).orElse(null);
        if (message == null) {
            message = history.saveAndFlush(new HistoryMessage(owner, id, account, peer, input));
        } else {
            if (!message.getAccount().equals(account) || !message.getPeer().equals(peer)
                    || !message.getDirection().equals(input.direction())
                    || !message.getBody().equals(input.body()) || message.getTime() != input.time())
                throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_CONFLICT", "Message id already has different content.");
            message.finish(input.status());
        }
        return HistoryEntry.from(message);
    }

    public CursorPage<HistoryEntry> messages(UUID owner, String peer, String before, int size) {
        var rows = history.messages(owner, normalize(peer), cursor(before, size), PageRequest.of(0, size + 1));
        return new CursorPage<>(rows.stream().limit(size).map(HistoryEntry::from).toList(), next(rows, size));
    }

    public CursorPage<Conversation> conversations(UUID owner, String before, int size) {
        var rows = history.conversations(owner, cursor(before, size), PageRequest.of(0, size + 1));
        return new CursorPage<>(rows.stream().limit(size)
                .map(m -> new Conversation(m.getPeer(), m.getName(), HistoryEntry.from(m))).toList(), next(rows, size));
    }

    private String next(List<HistoryMessage> rows, int size) {
        return rows.size() > size ? rows.get(size - 1).getSequence().toString() : null;
    }

    private long cursor(String before, int size) {
        if (size < 1 || size > 100) throw invalid("size must be 1–100.");
        if (before == null) return Long.MAX_VALUE;
        try {
            if (!before.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long result = Long.parseLong(before);
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw invalid("Invalid history cursor."); }
    }

    static String normalize(String value) {
        if (value == null || value.length() > 320) throw invalid("Invalid SIP address.");
        String address = value.strip().replaceFirst("(?i)^sips?:", "").split("[;?]", 2)[0];
        int at = address.lastIndexOf('@');
        if (at < 1 || at == address.length() - 1 || address.matches(".*[\\s<>].*")) throw invalid("Invalid SIP address.");
        String normalized = "sip:" + address.substring(0, at) + "@" + address.substring(at + 1).toLowerCase(Locale.ROOT);
        if (normalized.length() > 320) throw invalid("Invalid SIP address.");
        return normalized;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
