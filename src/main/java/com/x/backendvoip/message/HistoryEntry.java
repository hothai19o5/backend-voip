package com.x.backendvoip.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

public record HistoryEntry(String id, String sequence, String account, String peer, String name,
                           String direction, String body, String status, long time, String senderName, UUID peerUserId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) MessageAttachment.Metadata attachment) {
    static HistoryEntry from(HistoryMessage message) {
        return from(message, null);
    }

    static HistoryEntry from(HistoryMessage message, UUID peerUserId) {
        var attachment = message.getAttachment();
        return new HistoryEntry(message.getMessageId(), message.getSequence().toString(), message.getAccount(),
                message.getPeer(), message.getName(), message.getDirection(), message.getBody(),
                message.getStatus(), message.getTime(), message.getSenderName(), peerUserId,
                attachment == null ? null : attachment.metadata());
    }
}
