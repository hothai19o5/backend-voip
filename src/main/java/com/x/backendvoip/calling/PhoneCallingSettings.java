package com.x.backendvoip.calling;

import com.x.backendvoip.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(PhoneCallingProperties.class)
@RequiredArgsConstructor
public class PhoneCallingSettings {
    private final PhoneCallingProperties properties;
    private final UserRepository users;

    public boolean available() {
        return properties.enabled() && !users.existsByPhoneE164IsNull();
    }
}
