package com.x.backendvoip.user;

import com.x.backendvoip.common.ApiException;
import com.x.backendvoip.common.PageResponse;
import com.x.backendvoip.calling.PhoneCallingSettings;
import com.x.backendvoip.presence.PresenceService;
import com.x.backendvoip.sip.SipProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final SipProvisioningService sip;
    private final PresenceService presence;
    private final PhoneCallingSettings phoneCalling;

    public UserProfile me(UUID userId) {
        var user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."));
        return UserProfile.from(user, sip.summary(userId), phoneCalling.available());
    }

    public PageResponse<UserSummary> list(UUID callerId, String query, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "page must be >= 0 and size must be 1–100; offset must fit a 32-bit integer.");
        }
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (search.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "q must be at most 100 characters.");
        }
        Specification<AppUser> specification = (root, criteria, cb) -> cb.notEqual(root.get("id"), callerId);
        if (!search.isEmpty()) {
            String pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            specification = specification.and((root, criteria, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern, '\\'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '\\')));
        }
        var pageable = PageRequest.of(page, size, Sort.by("username").ascending().and(Sort.by("id")));
        var result = users.findAll(specification, pageable);
        var calling = sip.summaries(result.stream().map(AppUser::getId).toList());
        var online = presence.onlineUserIds();
        return PageResponse.from(result.map(user ->
                UserSummary.from(user, calling.get(user.getId()), online.contains(user.getId()))));
    }
}
