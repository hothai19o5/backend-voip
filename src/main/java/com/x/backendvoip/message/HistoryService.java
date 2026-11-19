package com.x.backendvoip.message;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.sip.ProvisioningStatus;
import com.x.backendvoip.sip.SipAccountRepository;
import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.x.backendvoip.sip.SipAddress.normalize;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {
    private static final String CHAT_PREFIX = "chat:";
    private final HistoryRepository history;
    private final EntityManager entities;
    private final SipAccountRepository sipAccounts;
    private final Clock clock;
    private final AttachmentStorage storage;

    public record CursorPage<T>(List<T> content, String nextCursor) {}
    public record Conversation(String peer, String name, HistoryEntry lastMessage) {}
    public record AttachmentContent(MessageAttachment.Metadata metadata, InputStream stream) {}

    @Transactional
    public HistoryEntry save(UUID ownerId, String id, HistoryWrite input) {
        if (!id.matches("[A-Za-z0-9_.:-]{1,100}") || id.startsWith(CHAT_PREFIX))
            throw invalid("Invalid message id.");
        if (input.direction().equals("incoming") != input.status().equals("received"))
            throw invalid("Incoming messages require received status; outgoing messages cannot be received.");
        String peer = normalize(input.peer()), account = normalize(input.account());
        // Serializes concurrent retries for this owner, including first insert. No
        // process-local lock: multiple backend instances obey the same DB lock.
        var owner = lockOwner(ownerId);
        var message = history.findByOwnerIdAndMessageId(ownerId, id).orElse(null);
        if (message == null) {
            message = history.saveAndFlush(new HistoryMessage(owner, id, account, peer, input));
        } else {
            if (!message.getAccount().equals(account) || !message.getPeer().equals(peer)
                    || !message.getDirection().equals(input.direction())
                    || !message.getBody().equals(input.body()) || message.getTime() != input.time())
                throw conflict();
            message.finish(input.status());
        }
        return HistoryEntry.from(message);
    }

    @Transactional
    public HistoryEntry send(UUID senderId, String requestId, DirectMessageWrite input) {
        return sendDirect(senderId, requestId, input.peer(), input.body(), null, null);
    }

    @Transactional
    public HistoryEntry sendAttachment(UUID senderId, String requestId, String peer, MultipartFile file) {
        var attachment = MessageAttachment.inspect(file);
        return sendDirect(senderId, requestId, peer, attachment.getFilename(), attachment, file);
    }

    public AttachmentContent attachment(UUID ownerId, String messageId) {
        var attachment = history.findByOwnerIdAndMessageId(ownerId, messageId)
                .map(HistoryMessage::getAttachment)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment not found."));
        return new AttachmentContent(attachment.metadata(), storage.download(attachment));
    }

    private HistoryEntry sendDirect(UUID senderId, String requestId, String address, String body,
                                    MessageAttachment attachment, MultipartFile file) {
        if (!requestId.matches("[A-Za-z0-9_.:-]{1,58}")) throw invalid("Invalid message id.");
        if (body.indexOf(0) >= 0) throw invalid("Invalid message body.");
        String peer = normalize(address);
        String id = CHAT_PREFIX + senderId + ":" + requestId;
        var existing = history.findByOwnerIdAndMessageId(senderId, id).orElse(null);
        if (existing != null) return retryDirect(existing, peer, body, attachment);

        int at = peer.lastIndexOf('@');
        var matches = sipAccounts.findFirst2ByDomainNameIgnoreCaseAndExtension(
                peer.substring(at + 1), peer.substring(4, at));
        if (matches.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "SIP_ONLY", "The address has no web chat account.");
        if (matches.size() != 1 || matches.getFirst().getProvisioningStatus() != ProvisioningStatus.READY)
            throw unavailable(HttpStatus.CONFLICT);
        UUID recipientId = matches.getFirst().getUser().getId();
        if (senderId.equals(recipientId)) throw invalid("You cannot send messages to yourself.");

        // All writes take their owner's lock before allocating a sequence. Lock
        // both accounts in a consistent order for concurrent messages in either direction.
        boolean senderFirst = senderId.compareTo(recipientId) < 0;
        var first = lockOwner(senderFirst ? senderId : recipientId);
        var second = lockOwner(senderFirst ? recipientId : senderId);
        var sender = senderFirst ? first : second;
        var recipient = senderFirst ? second : first;
        existing = history.findByOwnerIdAndMessageId(senderId, id).orElse(null);
        if (existing != null) return retryDirect(existing, peer, body, attachment);
        if (sender.getStatus() != UserStatus.ACTIVE || recipient.getStatus() != UserStatus.ACTIVE)
            throw unavailable(HttpStatus.FORBIDDEN);

        var account = sipAccounts.findByUserId(senderId)
                .filter(mapping -> mapping.getProvisioningStatus() == ProvisioningStatus.READY)
                .orElseThrow(() -> unavailable(HttpStatus.CONFLICT));
        String from = normalize(account.getExtension() + "@" + account.getDomainName());
        if (attachment != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) storage.discard(attachment);
                }
            });
            storage.upload(attachment, file);
        }
        long time = clock.millis();
        var outgoing = new HistoryWrite(from, peer, recipient.getDisplayName(), "outgoing",
                body, "accepted", time, null);
        var incoming = new HistoryWrite(peer, from, sender.getDisplayName(), "incoming",
                body, "received", time, sender.getDisplayName());
        var sent = history.save(new HistoryMessage(sender, id, from, peer, outgoing, attachment));
        history.save(new HistoryMessage(recipient, id, peer, from, incoming, attachment));
        return HistoryEntry.from(sent, recipientId);
    }

    private HistoryEntry retryDirect(HistoryMessage existing, String peer, String body, MessageAttachment attachment) {
        if (!existing.getPeer().equals(peer) || !existing.getBody().equals(body)) throw conflict();
        var stored = existing.getAttachment();
        if (stored == null ? attachment != null : !stored.matches(attachment)) throw conflict();
        return HistoryEntry.from(existing, peerUserIds(List.of(existing)).get(peer));
    }

    private AppUser lockOwner(UUID id) {
        var user = entities.find(AppUser.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
        return user;
    }

    public CursorPage<HistoryEntry> messages(UUID owner, String peer, String before, int size) {
        return page(history.messages(owner, normalize(peer), cursor(before, size), PageRequest.of(0, size + 1)), size);
    }

    public CursorPage<HistoryEntry> updates(UUID owner, String after, int size) {
        validateSize(size);
        return page(history.updates(owner, parseCursor(after, 0), PageRequest.of(0, size + 1)), size);
    }

    public CursorPage<Conversation> conversations(UUID owner, String before, int size) {
        var rows = history.conversations(owner, cursor(before, size), PageRequest.of(0, size + 1));
        var entries = page(rows, size);
        return new CursorPage<>(entries.content().stream()
                .map(entry -> new Conversation(entry.peer(), entry.name(), entry)).toList(), entries.nextCursor());
    }

    private String next(List<HistoryMessage> rows, int size) {
        return rows.size() > size ? rows.get(size - 1).getSequence().toString() : null;
    }

    private CursorPage<HistoryEntry> page(List<HistoryMessage> rows, int size) {
        var visible = rows.subList(0, Math.min(rows.size(), size));
        var peerIds = peerUserIds(visible);
        return new CursorPage<>(visible.stream()
                .map(message -> HistoryEntry.from(message, peerIds.get(message.getPeer()))).toList(), next(rows, size));
    }

    private Map<String, UUID> peerUserIds(List<HistoryMessage> messages) {
        if (messages.isEmpty()) return Map.of();
        var peers = messages.stream().map(HistoryMessage::getPeer).distinct().toList();
        var matches = sipAccounts.forPeers(peers).stream()
                .collect(Collectors.groupingBy(SipAccountRepository.PeerMapping::getPeer));
        return matches.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1
                        && entry.getValue().getFirst().getProvisioningStatus() == ProvisioningStatus.READY)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getFirst().getUserId()));
    }

    private long cursor(String before, int size) {
        validateSize(size);
        return before == null ? Long.MAX_VALUE : parseCursor(before, 1);
    }

    private void validateSize(int size) {
        if (size < 1 || size > 100) throw invalid("size must be 1–100.");
    }

    private long parseCursor(String value, int minimum) {
        try {
            if (!value.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long result = Long.parseLong(value);
            if (result < minimum) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) { throw invalid("Invalid history cursor."); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "MESSAGE_CONFLICT", "Message id already has different content.");
    }

    private static ApiException unavailable(HttpStatus status) {
        return new ApiException(status, "CHAT_UNAVAILABLE", "This account is unavailable for web chat.");
    }
}
