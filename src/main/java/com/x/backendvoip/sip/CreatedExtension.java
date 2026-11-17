package com.x.backendvoip.sip;

import java.util.UUID;

public record CreatedExtension(UUID id, String number, String password) {
    @Override
    public String toString() {
        return "CreatedExtension[id=" + id + ", number=" + number + ", password=REDACTED]";
    }
}
