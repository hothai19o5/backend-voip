package com.x.backendvoip.message;

import java.util.UUID;

public record HistoryEntry(String id, String sequence, String account, String peer, String name,
                           String direction, String body, String status, long time, String senderName, UUID peerUserId) {
    static HistoryEntry from(HistoryMessage message) {
        return from(message, null);
    }

    static HistoryEntry from(HistoryMessage message, UUID peerUserId) {
        return new HistoryEntry(message.getMessageId(), message.getSequence().toString(), message.getAccount(),
                message.getPeer(), message.getName(), message.getDirection(), message.getBody(),
                message.getStatus(), message.getTime(), message.getSenderName(), peerUserId);
    }
}
