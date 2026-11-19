package com.x.backendvoip.calling;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallController {
    private final CallService calls;
    private final PhoneCallService phones;

    @PostMapping("/resolve")
    public PhoneCallService.Destination resolve(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody PhoneCallService.ResolveRequest request) {
        return phones.resolve(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping("/dialed")
    public List<PhoneCallService.Recent> dialed(@AuthenticationPrincipal Jwt jwt) {
        return phones.recent(UUID.fromString(jwt.getSubject()));
    }

    public record Seen(@NotBlank String through, @Size(max = 320) String peer) {}

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CallWrite input) {
        calls.save(UUID.fromString(jwt.getSubject()), input);
    }

    @GetMapping
    public CallService.Page<CallService.Entry> page(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean missed, @RequestParam(required = false) String peer,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "30") int size) {
        return calls.page(UUID.fromString(jwt.getSubject()), missed, before, size, peer);
    }

    @GetMapping("/threads")
    public CallService.Page<CallService.Thread> threads(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "30") int size) {
        return calls.threads(UUID.fromString(jwt.getSubject()), before, size);
    }

    @GetMapping("/missed")
    public CallService.Missed missed(@AuthenticationPrincipal Jwt jwt) {
        return calls.missed(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/missed")
    public CallService.Missed markSeen(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Seen input) {
        return calls.markSeen(UUID.fromString(jwt.getSubject()), input.through(), input.peer());
    }
}
