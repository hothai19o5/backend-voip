package com.x.backendvoip.calling;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.phone-calls")
public record PhoneCallingProperties(boolean enabled) {}
