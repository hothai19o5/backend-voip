package com.x.backendvoip.message;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/message-history")
@RequiredArgsConstructor
public class HistoryController {
    private final HistoryService history;

    @PutMapping("/send/{id}")
    public HistoryEntry send(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                             @Valid @RequestBody DirectMessageWrite input) {
        return history.send(UUID.fromString(jwt.getSubject()), id, input);
    }

    @PutMapping(value = "/attachments/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HistoryEntry sendAttachment(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                       @RequestParam String peer, @RequestParam MultipartFile file) {
        return history.sendAttachment(UUID.fromString(jwt.getSubject()), id, peer, file);
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<InputStreamResource> attachment(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable String id) {
        var content = history.attachment(UUID.fromString(jwt.getSubject()), id);
        var metadata = content.metadata();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(metadata.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(metadata.size())
                .body(new InputStreamResource(content.stream()));
    }

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

    @GetMapping("/updates")
    public HistoryService.CursorPage<HistoryEntry> updates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String after, @RequestParam(defaultValue = "100") int size) {
        return history.updates(UUID.fromString(jwt.getSubject()), after, size);
    }

    @GetMapping("/conversations")
    public HistoryService.CursorPage<HistoryService.Conversation> conversations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "30") int size) {
        return history.conversations(UUID.fromString(jwt.getSubject()), before, size);
    }
}
