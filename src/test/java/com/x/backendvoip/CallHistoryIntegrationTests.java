package com.x.backendvoip;

import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CallHistoryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    AppUser alice;
    AppUser bob;

    @BeforeEach
    void setup() {
        alice = user("alice");
        bob = user("bob");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from call_records where owner_id in (?, ?)", alice.getId(), bob.getId());
        users.deleteAll(java.util.List.of(alice, bob));
    }

    @Test
    void historyIsPrivateFilteredAndOrderedByTimeWithStableCursor() throws Exception {
        save(alice, call("new", "MISSED", 200)).andExpect(status().isNoContent());
        save(alice, call("old", "MISSED", 100)).andExpect(status().isNoContent());
        var outgoing = call("out", "CANCELED", 200);
        outgoing.put("direction", "OUTGOING");
        save(alice, outgoing).andExpect(status().isNoContent());

        String first = read(alice, "?size=1")
                .andExpect(jsonPath("$.content[0].status").value("CANCELED"))
                .andReturn().getResponse().getContentAsString();
        read(alice, "?before=" + json.readTree(first).get("nextCursor").asString())
                .andExpect(jsonPath("$.content[0].startedAt").value(200))
                .andExpect(jsonPath("$.content[1].startedAt").value(100));
        read(alice, "?missed=true")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].peer").value("sip:1002@pbx.test"));
        read(bob, "").andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get("/api/v1/calls")).andExpect(status().isUnauthorized());
    }

    @Test
    void acknowledgingAMissedSnapshotLeavesNewerMissedCallsUnread() throws Exception {
        save(alice, call("first", "MISSED", 200)).andExpect(status().isNoContent());
        String snapshot = read(alice, "/missed").andExpect(jsonPath("$.unread").value(1))
                .andReturn().getResponse().getContentAsString();
        String through = json.readTree(snapshot).get("through").asString();
        save(alice, call("second", "MISSED", 300)).andExpect(status().isNoContent());
        markSeen(bob, through).andExpect(jsonPath("$.unread").value(0));
        read(alice, "/missed").andExpect(jsonPath("$.unread").value(2));
        markSeen(alice, through).andExpect(jsonPath("$.unread").value(1));
        save(alice, call("first", "MISSED", 200)).andExpect(status().isNoContent());
        read(alice, "?missed=true")
                .andExpect(jsonPath("$.content[0].unread").value(true))
                .andExpect(jsonPath("$.content[1].unread").value(false));
    }

    @Test
    void concurrentIncomingReportsDeduplicateAndConfirmedAnswerWins() throws Exception {
        var missed = call("shared", "MISSED", 100);
        var answered = call("shared", "COMPLETED", 101);
        answered.put("answeredAt", 102);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> save(alice, missed).andExpect(status().isNoContent()));
            var two = pool.submit(() -> save(alice, answered).andExpect(status().isNoContent()));
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        save(alice, missed).andExpect(status().isNoContent());
        read(alice, "").andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].answeredAt").value(102))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
        read(alice, "/missed").andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void chatThreadsShowLatestEndedCallAndOnlyAcknowledgeTheOpenedPeer() throws Exception {
        save(alice, call("older", "MISSED", 200)).andExpect(status().isNoContent());
        var otherPeer = call("another-peer", "MISSED", 300);
        otherPeer.put("peer", "sip:1003@pbx.test");
        save(alice, otherPeer).andExpect(status().isNoContent());
        var longCall = call("ended-last", "COMPLETED", 100);
        longCall.put("answeredAt", 120);
        longCall.put("endedAt", 400);
        save(alice, longCall).andExpect(status().isNoContent());

        String first = read(alice, "/threads?size=1")
                .andExpect(jsonPath("$.content[0].lastCall.id").value("ended-last"))
                .andExpect(jsonPath("$.content[0].missed.unread").value(1))
                .andReturn().getResponse().getContentAsString();
        var page = json.readTree(first);
        read(alice, "/threads?before=" + page.get("nextCursor").asString())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].lastCall.peer").value("sip:1003@pbx.test"));
        read(bob, "/threads").andExpect(jsonPath("$.content.length()").value(0));
        String thread = read(alice, "?peer=sip:1002@PBX.test&size=1")
                .andExpect(jsonPath("$.content[0].id").value("ended-last"))
                .andReturn().getResponse().getContentAsString();
        read(alice, "?peer=sip:1002@pbx.test&before=" + json.readTree(thread).get("nextCursor").asString())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("older"));

        // Use the global sequence to prove that reading one peer leaves the other peer unread.
        String summary = read(alice, "/missed").andReturn().getResponse().getContentAsString();
        String through = json.readTree(summary).get("through").asString();
        mvc.perform(put("/api/v1/calls/missed").with(jwt().jwt(j -> j.subject(alice.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("through", through, "peer", "sip:1002@pbx.test"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(0));
        read(alice, "?peer=sip:1003@pbx.test")
                .andExpect(jsonPath("$.content[0].unread").value(true));
    }

    @Test
    void invalidStatusDirectionAndTimelineAreRejected() throws Exception {
        var invalid = call("invalid", "MISSED", 100);
        invalid.put("direction", "OUTGOING");
        save(alice, invalid).andExpect(status().isBadRequest());
        invalid.put("status", "COMPLETED");
        invalid.put("answeredAt", 120);
        save(alice, invalid).andExpect(status().isBadRequest());
        read(alice, "?before=abc").andExpect(status().isBadRequest());
        read(alice, "?size=101").andExpect(status().isBadRequest());
        read(alice, "").andExpect(jsonPath("$.content.length()").value(0));
    }

    private AppUser user(String name) {
        String username = name + "_call_" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(new AppUser(username, username + "@test.test", "unused", name, Instant.now()));
    }

    private Map<String, Object> call(String id, String status, long start) {
        return new LinkedHashMap<>(Map.of("id", id, "peer", "sips:1002@PBX.test;transport=ws",
                "name", "Trần Hoàng Nam", "direction", "INCOMING", "status", status,
                "video", false, "startedAt", start, "endedAt", start + 10));
    }

    private ResultActions save(AppUser user, Map<String, Object> call) throws Exception {
        return mvc.perform(post("/api/v1/calls").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(call)));
    }

    private ResultActions read(AppUser user, String suffix) throws Exception {
        return mvc.perform(get("/api/v1/calls" + suffix).with(jwt().jwt(j -> j.subject(user.getId().toString()))));
    }

    private ResultActions markSeen(AppUser user, String through) throws Exception {
        return mvc.perform(put("/api/v1/calls/missed").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("through", through))));
    }
}
