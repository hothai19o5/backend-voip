package com.x.backendvoip.user;

import com.x.backendvoip.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService users;

    public record ContactPhone(String phoneNumber) {}

    @GetMapping("/contact-phone")
    public ResponseEntity<ContactPhone> contactPhone(@RequestParam String peer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ContactPhone(users.contactPhone(peer)));
    }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return users.me(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping
    public PageResponse<UserSummary> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return users.list(UUID.fromString(jwt.getSubject()), q, page, size);
    }
}
