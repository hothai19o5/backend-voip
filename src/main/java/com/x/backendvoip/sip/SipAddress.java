package com.x.backendvoip.sip;

import com.x.backendvoip.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

public final class SipAddress {
    private SipAddress() {}

    public static String normalize(String value) {
        if (value == null || value.length() > 320) throw invalid();
        String address = value.strip().replaceFirst("(?i)^sips?:", "").split("[;?]", 2)[0];
        int at = address.lastIndexOf('@');
        if (at < 1 || at == address.length() - 1 || address.matches(".*[\\s<>].*")) throw invalid();
        String normalized = "sip:" + address.substring(0, at) + "@" + address.substring(at + 1).toLowerCase(Locale.ROOT);
        if (normalized.length() > 320) throw invalid();
        return normalized;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid SIP address.");
    }
}
