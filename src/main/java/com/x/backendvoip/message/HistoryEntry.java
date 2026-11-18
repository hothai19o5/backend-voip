package com.x.backendvoip.message;

public record HistoryEntry(String id, String sequence, String account, String peer, String name,
                           String direction, String body, String status, long time, String senderName) {
    static HistoryEntry from(HistoryMessage message) {
        return new HistoryEntry(message.getMessageId(), message.getSequence().toString(), message.getAccount(),
                message.getPeer(), message.getName(), message.getDirection(), message.getBody(),
                message.getStatus(), message.getTime(), message.getSenderName());
    }
}
