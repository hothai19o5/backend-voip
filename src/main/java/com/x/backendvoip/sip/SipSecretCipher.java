package com.x.backendvoip.sip;

import com.x.backendvoip.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SipSecretCipher {
    private final FusionPbxProperties properties;
    private final SecureRandom random = new SecureRandom();

    public void requireKey() { properties.encryptionKey(); }

    public String encrypt(UUID accountId, String password) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, accountId, nonce);
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw unavailable();
        }
    }

    public String decrypt(UUID accountId, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, accountId, nonce)
                    .doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, UUID accountId, byte[] nonce) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(properties.encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "Account calling credentials are unavailable. Contact the administrator.");
    }
}
