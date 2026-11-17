package com.x.backendvoip.sip;

import com.x.backendvoip.common.ApiException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Getter
@Setter
@ConfigurationProperties("app.fusionpbx")
public class FusionPbxProperties {
    private URI apiUrl;
    private String apiToken;
    private String domainName;
    private String serverIp;
    private URI wssUrl;
    private String credentialsKey;
    private int extensionStart;
    private int extensionEnd;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private boolean verifySsl = true;

    public void requireApiConfiguration() {
        try {
            if (apiUrl == null || apiUrl.getHost() == null || apiUrl.getUserInfo() != null
                    || apiUrl.getQuery() != null || apiUrl.getFragment() != null
                    || !("https".equals(apiUrl.getScheme()) || "http".equals(apiUrl.getScheme()))
                    || domainName == null || domainName.isBlank() || apiToken == null) throw new IllegalArgumentException();
            String[] credentials = apiToken.split(":", 2);
            if (credentials.length != 2 || credentials[1].isBlank()) throw new IllegalArgumentException();
            UUID.fromString(credentials[0]);
            if (extensionStart < 1 || extensionEnd < extensionStart || extensionEnd > 99999999
                    || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw notConfigured();
        }
    }

    public byte[] encryptionKey() {
        try {
            byte[] key = Base64.getDecoder().decode(credentialsKey == null ? "" : credentialsKey);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException error) {
            throw notConfigured();
        }
    }

    private ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_NOT_CONFIGURED",
                "Account calling service is not configured. Contact the administrator.");
    }
}
