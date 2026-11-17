package com.x.backendvoip.sip;

import java.util.List;
import java.util.UUID;

public record PbxSnapshot(UUID domainId, List<PbxExtension> extensions) {
    public boolean contains(UUID extensionId, String number) {
        return extensions.stream().anyMatch(extension -> extension.id().equals(extensionId) && extension.number().equals(number));
    }
}
