package com.x.backendvoip.message;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/message-history")
@RequiredArgsConstructor
public class HistoryController {
    private final HistoryService history;

    @PutMapping("/messages/{id}")
    public HistoryEntry save(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                             @Valid @RequestBody HistoryWrite input) {
        return history.save(UUID.fromString(jwt.getSubject()), id, input);
    }

    @GetMapping("/messages")
    public HistoryService.CursorPage<HistoryEntry> messages(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String peer, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int size) {
        return history.messages(UUID.fromString(jwt.getSubject()), peer, before, size);
    }

    @GetMapping("/conversations")
    public HistoryService.CursorPage<HistoryService.Conversation> conversations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "30") int size) {
        return history.conversations(UUID.fromString(jwt.getSubject()), before, size);
    }
}
