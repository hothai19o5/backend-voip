package com.x.backendvoip.sip;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SipProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(SipProvisioningService.class);
    private final FusionPbxClient pbx;
    private final FusionPbxProperties properties;
    private final SipSecretCipher secrets;
    private final SipAccountRepository accounts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PbxSnapshot prepare() {
        properties.requireApiConfiguration();
        secrets.requireKey();
        return pbx.snapshot();
    }

    /** Called inside the same transaction that reserves the new app user. */
    public SipAccount reserve(AppUser user, PbxSnapshot snapshot) {
        var used = new HashSet<>(accounts.reservedExtensions(snapshot.domainId()));
        snapshot.extensions().forEach(extension -> used.add(extension.number()));
        for (int number = properties.getExtensionStart(); number <= properties.getExtensionEnd(); number++) {
            if (!used.contains(Integer.toString(number))) {
                return accounts.saveAndFlush(new SipAccount(user, snapshot.domainId(), properties.getDomainName(),
                        Integer.toString(number), clock.instant()));
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "No calling accounts are available. Contact the administrator.");
    }

    public SipSummary complete(SipAccount reservation, String displayName) {
        // Reservation is already committed. Never retry this non-idempotent HTTP write.
        try {
            var remote = pbx.createExtension(reservation.getDomainId(), reservation.getExtension(), displayName);
            String encrypted = secrets.encrypt(reservation.getId(), remote.password());
            return transactions.execute(status -> {
                var account = accounts.findById(reservation.getId()).orElseThrow();
                account.complete(remote.id(), encrypted, clock.instant());
                accounts.saveAndFlush(account);
                return account.summary(true);
            });
        } catch (RuntimeException error) {
            ProvisioningStatus outcome = error instanceof FusionPbxException upstream && upstream.isDefinitiveRejection()
                    ? ProvisioningStatus.FAILED : ProvisioningStatus.UNKNOWN;
            try {
                transactions.executeWithoutResult(status -> {
                    var account = accounts.findById(reservation.getId()).orElseThrow();
                    account.fail(outcome, clock.instant());
                    accounts.saveAndFlush(account);
                });
            } catch (RuntimeException persistenceError) {
                log.error("Could not persist provisioning outcome for account {}. Its reservation needs reconciliation.", reservation.getId());
            }
            if (error instanceof ApiException apiError) throw apiError;
            throw pending();
        }
    }

    /** Provision pre-integration app users only after their app password was verified. */
    public SipSummary ensureProvisioned(AppUser user) {
        var existing = accounts.findByUserId(user.getId());
        if (existing.isPresent()) return requireReady(existing.get()).summary(null);
        PbxSnapshot snapshot = prepare();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                var reservation = transactions.execute(status -> {
                    var concurrent = accounts.findByUserId(user.getId());
                    if (concurrent.isPresent()) return new Reservation(concurrent.get(), false);
                    return new Reservation(reserve(user, snapshot), true);
                });
                if (reservation == null) throw pending();
                return reservation.created ? complete(reservation.account, user.getDisplayName())
                        : requireReady(reservation.account).summary(null);
            } catch (DataIntegrityViolationException collision) {
                var concurrent = accounts.findByUserId(user.getId());
                if (concurrent.isPresent()) return requireReady(concurrent.get()).summary(null);
                // Another app instance reserved the candidate number; retry local allocation only.
            }
        }
        throw pending();
    }

    public Map<UUID, SipSummary> summaries(Collection<UUID> userIds) {
        // Required even for an empty local page: a successful directory must reflect a real PBX read.
        PbxSnapshot snapshot = pbx.snapshot();
        if (userIds.isEmpty()) return Map.of();
        return accounts.forUsers(userIds).stream().collect(Collectors.toMap(account -> account.getUser().getId(),
                account -> account.summary(present(account, snapshot))));
    }

    public SipSummary summary(UUID userId) {
        return summaries(List.of(userId)).get(userId);
    }

    public SipCredentials credentials(UUID userId) {
        var account = accounts.findByUserId(userId).map(this::requireReady).orElseThrow(this::pending);
        if (!present(account, pbx.snapshot())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "Your calling account is unavailable. Contact the administrator.");
        }
        if (properties.getWssUrl() == null || properties.getServerIp() == null) throw pending();
        return new SipCredentials(account.getExtension(), account.getDomainName(), properties.getServerIp(),
                properties.getWssUrl().toString(), secrets.decrypt(account.getId(), account.getEncryptedPassword()));
    }

    private boolean present(SipAccount account, PbxSnapshot snapshot) {
        return account.getDomainId().equals(snapshot.domainId())
                && snapshot.contains(account.getExtensionId(), account.getExtension());
    }

    private SipAccount requireReady(SipAccount account) {
        if (account.getProvisioningStatus() != ProvisioningStatus.READY) throw pending();
        return account;
    }

    private ApiException pending() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVISIONING_PENDING", "Your account setup is pending. Contact the administrator.");
    }

    private record Reservation(SipAccount account, boolean created) {}
}
