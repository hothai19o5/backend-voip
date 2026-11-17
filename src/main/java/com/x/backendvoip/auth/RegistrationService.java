package com.x.backendvoip.auth;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.sip.*;
import com.x.backendvoip.user.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegistrationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SipProvisioningService sip;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public UserProfile register(RegisterRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        String email = request.email().toLowerCase(Locale.ROOT);
        checkUnique(username, email);
        PbxSnapshot snapshot = sip.prepare();
        String passwordHash = passwords.encode(request.password());
        for (int attempt = 0; attempt < 5; attempt++) {
            Reservation reservation;
            try {
                reservation = transactions.execute(status -> {
                    checkUnique(username, email);
                    var user = users.saveAndFlush(new AppUser(username, email, passwordHash, request.displayName(), clock.instant()));
                    return new Reservation(user, sip.reserve(user, snapshot));
                });
            } catch (DataIntegrityViolationException collision) {
                checkUnique(username, email);
                // Unique domain/extension reservation lost to another node: no remote write has happened.
                continue;
            }
            if (reservation == null) break;
            var calling = sip.complete(reservation.account, request.displayName());
            return UserProfile.from(reservation.user, calling);
        }
        throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", "Account setup encountered a conflict. Please try again.");
    }

    private void checkUnique(String username, String email) {
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Username is already registered.");
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered.");
        }
    }

    private record Reservation(AppUser user, SipAccount account) {}
}
