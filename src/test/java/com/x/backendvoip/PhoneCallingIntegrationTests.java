package com.x.backendvoip;

import com.x.backendvoip.sip.SipAccount;
import com.x.backendvoip.sip.SipAccountRepository;
import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.user.UserRepository;
import com.x.backendvoip.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:phone-calls;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.phone-calls.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhoneCallingIntegrationTests {
    private static final String DOMAIN = "pbx.example.test";
    private static final String RESOLVE = "/api/v1/calls/resolve";
    private static final String DIAL_HISTORY = "/api/v1/calls/dialed";
    private static final UUID PBX_DOMAIN = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired SipAccountRepository sipAccounts;
    AppUser alice;
    AppUser bob;

    @BeforeEach
    void seed() {
        jdbc.update("delete from dialed_calls");
        jdbc.update("delete from call_records");
        sipAccounts.deleteAllInBatch();
        users.deleteAllInBatch();
        alice = user("alice", "+84900000001", "1001");
        bob = user("bob", "+84900000002", "1002");
    }

    @Test
    void resolvesMatchedNumberToExtensionWithoutPresenceAndRejectsUnavailableAccounts() throws Exception {
        resolve("090 000 0002").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APP_TO_APP"))
                .andExpect(jsonPath("$.phoneNumber").value("+84900000002"))
                .andExpect(jsonPath("$.sipUri").value("sip:1002@" + DOMAIN));
        bob.changeStatus(UserStatus.BLOCKED);
        users.saveAndFlush(bob);
        resolve("+84900000002").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APP_DESTINATION_UNAVAILABLE"));
        user("no-sip", "+84900000003", null);
        resolve("+84900000003").andExpect(status().isConflict());
        resolve("+84900000001").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_CALL"));
    }

    @Test
    void explicitlyRoutesAMatchedNumberThroughThePhoneNetwork() throws Exception {
        resolve(Map.of("phoneNumber", "090 000 0002", "forcePstn", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APP_TO_PHONE"))
                .andExpect(jsonPath("$.sipUri").value("sip:+84900000002@" + DOMAIN))
                .andExpect(jsonPath("$.displayName").value("bob"));
        resolve(Map.of("phoneNumber", "+84900000001", "forcePstn", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_CALL"));
        user("external", "+14155552671", "1003");
        resolve(Map.of("phoneNumber", "+14155552671", "forcePstn", true))
                .andExpect(status().isForbidden());
        bob.changeStatus(UserStatus.BLOCKED);
        users.saveAndFlush(bob);
        resolve(Map.of("phoneNumber", "+84900000002", "forcePstn", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APP_DESTINATION_UNAVAILABLE"));
    }

    @Test
    void resolvesUnassignedVietnamNumberAndProtectsTheLookup() throws Exception {
        resolve("(028) 3822 3456").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APP_TO_PHONE"))
                .andExpect(jsonPath("$.sipUri").value("sip:+842838223456@" + DOMAIN));
        resolve("0900000050").andExpect(status().isOk())
                .andExpect(jsonPath("$.sipUri").value("sip:+84900000050@" + DOMAIN));
        resolve("+14155552671").andExpect(status().isForbidden());
        resolve("090 x 14").andExpect(status().isBadRequest());
        mvc.perform(post(RESOLVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"0900000002\"}"))
                .andExpect(status().isUnauthorized());
        user("legacy", null, null);
        resolve("0900000050").andExpect(status().isServiceUnavailable());
    }

    @Test
    void storesPstnResultPrivatelyAndIdempotentlyOutsideChat() throws Exception {
        String peer = "sip:+842838223456@" + DOMAIN;
        save("pstn-sip-id", peer, "+842838223456").andExpect(status().isNoContent());
        save("pstn-sip-id", peer, "+842838223456").andExpect(status().isNoContent());
        save("pstn-sip-id", "sip:1002@" + DOMAIN, "+84900000002").andExpect(status().isConflict());
        mvc.perform(get(DIAL_HISTORY).with(jwt().jwt(j -> j.subject(alice.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].route").value("APP_TO_PHONE"));
        mvc.perform(get(DIAL_HISTORY).with(jwt().jwt(j -> j.subject(bob.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/calls/threads").with(jwt().jwt(j -> j.subject(alice.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void storesDialedAppCallInBothViewsUsingItsActualSipPeer() throws Exception {
        String peer = "sip:1002@" + DOMAIN;
        save("app-sip-id", peer, "+84900000002").andExpect(status().isNoContent());
        save("app-sip-id", peer, "+84900000002").andExpect(status().isNoContent());
        mvc.perform(get(DIAL_HISTORY).with(jwt().jwt(j -> j.subject(alice.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].route").value("APP_TO_APP"));
        mvc.perform(get("/api/v1/calls").param("peer", peer)
                        .with(jwt().jwt(j -> j.subject(alice.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("BUSY"));
    }

    private AppUser user(String name, String phone, String extension) {
        var user = users.saveAndFlush(new AppUser(name, name + "@example.test", "unused", name, phone, Instant.now()));
        if (extension != null) {
            var account = new SipAccount(user, PBX_DOMAIN, DOMAIN, extension, Instant.now());
            account.complete(UUID.randomUUID(), "unused", Instant.now());
            sipAccounts.saveAndFlush(account);
        }
        return user;
    }

    private ResultActions resolve(String number) throws Exception {
        return resolve(Map.of("phoneNumber", number));
    }

    private ResultActions resolve(Map<String, ?> body) throws Exception {
        return mvc.perform(post(RESOLVE).with(jwt().jwt(j -> j.subject(alice.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions save(String id, String peer, String phone) throws Exception {
        long now = System.currentTimeMillis();
        var body = Map.of("id", id, "peer", peer, "dialedPhoneNumber", phone, "video", false,
                "direction", "OUTGOING", "status", "BUSY", "startedAt", now - 1000, "endedAt", now);
        return mvc.perform(post("/api/v1/calls").with(jwt().jwt(j -> j.subject(alice.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
