package com.x.backendvoip.sip;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sip")
@RequiredArgsConstructor
public class SipController {
    private final SipProvisioningService sip;

    @GetMapping("/account")
    public SipCredentials account(@AuthenticationPrincipal Jwt token) {
        return sip.credentials(UUID.fromString(token.getSubject()));
    }
}
