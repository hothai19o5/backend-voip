package com.x.backendvoip.sip;

public record SipCredentials(String extension, String domain, String serverIp, String wssUrl, String password) {
    @Override
    public String toString() {
        return "SipCredentials[extension=" + extension + ", domain=" + domain + ", password=REDACTED]";
    }
}
