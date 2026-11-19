package com.x.backendvoip.calling;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.sip.ProvisioningStatus;
import com.x.backendvoip.sip.SipAccountRepository;
import com.x.backendvoip.user.PhoneNumbers;
import com.x.backendvoip.user.UserRepository;
import com.x.backendvoip.user.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PhoneCallService {
    private final PhoneCallingSettings settings;
    private final UserRepository users;
    private final SipAccountRepository sipAccounts;
    private final DialedCallRepository dialedCalls;

    public record ResolveRequest(@NotBlank @Size(max = 40) String phoneNumber) {}

    public record Destination(String type, String phoneNumber, String sipUri, String displayName) {}

    public record Recent(UUID id, String phoneNumber, String route, String name, CallWrite.Status status,
                         long startedAt, Long answeredAt, long endedAt) {
        static Recent from(DialedCall call) {
            return new Recent(call.getId(), call.getPhoneE164(), call.getRoute().name(), call.getDestinationName(),
                    call.getStatus(), call.getStartedAt(), call.getAnsweredAt(), call.getEndedAt());
        }
    }

    public Destination resolve(UUID callerId, ResolveRequest request) {
        if (!settings.available())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHONE_CALLING_DISABLED", "Phone dialing is disabled.");
        var phone = PhoneNumbers.parse(request.phoneNumber());
        var recipient = users.findByPhoneE164(phone.e164()).orElse(null);
        if (recipient != null) {
            if (recipient.getId().equals(callerId))
                throw new ApiException(HttpStatus.CONFLICT, "SELF_CALL", "You cannot dial your own number.");
            var account = sipAccounts.findByUserId(recipient.getId()).orElse(null);
            if (recipient.getStatus() != UserStatus.ACTIVE || account == null || account.getProvisioningStatus() != ProvisioningStatus.READY)
                throw new ApiException(HttpStatus.CONFLICT, "APP_DESTINATION_UNAVAILABLE", "The app destination is unavailable.");
            return new Destination("APP_TO_APP", phone.e164(), "sip:" + account.getExtension() + "@" + account.getDomainName(),
                    recipient.getDisplayName());
        }
        if (!phone.vietnamLandlineOrMobile())
            throw new ApiException(HttpStatus.FORBIDDEN, "PSTN_DESTINATION_DENIED", "Only Vietnamese mobile and landline PSTN numbers are supported.");
        var caller = sipAccounts.findByUserId(callerId).filter(account -> account.getProvisioningStatus() == ProvisioningStatus.READY)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CALLER_SIP_UNAVAILABLE", "The caller SIP account is unavailable."));
        return new Destination("APP_TO_PHONE", phone.e164(), "sip:" + phone.e164() + "@" + caller.getDomainName(), null);
    }

    public List<Recent> recent(UUID owner) {
        return dialedCalls.findTop30ByOwnerIdAndEndedAtIsNotNullOrderByEndedAtDescIdDesc(owner).stream().map(Recent::from).toList();
    }

    @Transactional
    DialedCall.Route recordDialed(UUID owner, String peer, CallWrite input) {
        var phone = PhoneNumbers.parse(input.dialedPhoneNumber());
        String sipUser = peer.substring(4, peer.indexOf('@'));
        boolean pstn = sipUser.equals(phone.e164());
        if (input.direction() != CallWrite.Direction.OUTGOING || input.video()
                || (pstn ? !phone.vietnamLandlineOrMobile() : !sipUser.matches("[0-9]{1,16}")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid result for a dialed phone call.");

        // CallService locks the caller row. Retries reuse the same call ID.
        UUID id = UUID.nameUUIDFromBytes((owner + ":" + input.id()).getBytes(StandardCharsets.UTF_8));
        var route = pstn ? DialedCall.Route.APP_TO_PHONE : DialedCall.Route.APP_TO_APP;
        var existing = dialedCalls.findById(id).orElse(null);
        if (existing == null) {
            dialedCalls.save(new DialedCall(id, owner, phone.e164(), peer, route, input));
        } else if (!peer.equals(existing.getSipPeer()) || !phone.e164().equals(existing.getPhoneE164()) || route != existing.getRoute()) {
            throw new ApiException(HttpStatus.CONFLICT, "CALL_CONFLICT", "This call id belongs to another dialed destination.");
        }
        return route;
    }
}
