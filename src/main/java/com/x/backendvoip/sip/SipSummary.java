package com.x.backendvoip.sip;

public record SipSummary(String extension, String domain, ProvisioningStatus provisioningStatus,
                         Boolean extensionPresent) {}
