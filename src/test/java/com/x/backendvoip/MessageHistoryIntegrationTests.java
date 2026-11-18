package com.x.backendvoip;

import com.x.backendvoip.message.HistoryRepository;
import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageHistoryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired HistoryRepository history;
    AppUser alice;
    AppUser bob;

    @BeforeEach
    void setup() {
        history.deleteAllInBatch();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = users.save(new AppUser("hist_a_" + suffix, suffix + "a@test.test", "unused", "Alice", Instant.now()));
        bob = users.save(new AppUser("hist_b_" + suffix, suffix + "b@test.test", "unused", "Bob", Instant.now()));
    }

    @AfterEach
    void cleanup() {
        history.deleteAllInBatch();
        users.delete(alice);
        users.delete(bob);
    }

    Map<String, Object> message(String status) {
        return new LinkedHashMap<>(Map.of("account", "sip:1001@PBX.test", "peer", "sips:1002@PBX.test;transport=ws",
                "name", "Bảo Nguyễn", "direction", "outgoing", "body", "Xin chào 👋\n<script>text only</script>",
                "status", status, "time", 123456789L));
    }

    ResultActions save(AppUser owner, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/message-history/messages/{id}", id)
                .with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions read(AppUser owner, String path) throws Exception {
        return mvc.perform(get("/api/v1/message-history/" + path)
                .with(jwt().jwt(j -> j.subject(owner.getId().toString()))));
    }

    @Test
    void savesUnicodeAndRestoresHistoryWithAnotherSessionWithoutPbx() throws Exception {
        save(alice, "local-1", message("accepted")).andExpect(status().isOk())
                .andExpect(jsonPath("$.peer").value("sip:1002@pbx.test"))
                .andExpect(jsonPath("$.account").value("sip:1001@pbx.test"));
        read(alice, "conversations").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Bảo Nguyễn"))
                .andExpect(jsonPath("$.content[0].lastMessage.id").value("local-1"));
        // A fresh principal/request has no client memory from the PUT.
        read(alice, "messages?peer=sip:1002@pbx.test").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].body").value(message("accepted").get("body")))
                .andExpect(jsonPath("$.content[0].status").value("accepted"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void retriesAreIdempotentAndStatusCannotRegressOrOverwriteContent() throws Exception {
        save(alice, "local-1", message("pending")).andExpect(status().isOk());
        save(alice, "local-1", message("accepted")).andExpect(status().isOk());
        save(alice, "local-1", message("pending")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
        save(alice, "local-1", message("failed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
        assertThat(history.count()).isEqualTo(1);
        var changed = message("accepted"); changed.put("body", "different");
        save(alice, "local-1", changed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MESSAGE_CONFLICT"));
    }

    @Test
    void accountHistoriesStaySeparateAndSameLocalIdCanBeUsedByDifferentOwners() throws Exception {
        save(alice, "same-id", message("accepted")).andExpect(status().isOk());
        read(bob, "conversations").andExpect(jsonPath("$.content").isEmpty());
        read(bob, "messages?peer=sip:1002@pbx.test").andExpect(jsonPath("$.content").isEmpty());
        var incoming = message("received"); incoming.put("direction", "incoming");
        save(bob, "same-id", incoming).andExpect(status().isOk());
        assertThat(history.count()).isEqualTo(2);
        read(alice, "messages?peer=sip:1002@pbx.test").andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void cursorsDoNotLoseEqualTimestampMessagesOrDuplicateConversations() throws Exception {
        for (int i = 0; i < 5; i++) save(alice, "local-" + i, message("accepted")).andExpect(status().isOk());
        var first = read(alice, "messages?peer=sip:1002@pbx.test&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("local-4")).andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).path("nextCursor").asString();
        read(alice, "messages?peer=sip:1002@pbx.test&size=2&before=" + cursor)
                .andExpect(jsonPath("$.content[0].id").value("local-2"))
                .andExpect(jsonPath("$.content[1].id").value("local-1"));
        var another = message("failed"); another.put("peer", "sip:1003@pbx.test");
        save(alice, "another", another).andExpect(status().isOk());
        var list = read(alice, "conversations?size=1").andExpect(jsonPath("$.content[0].peer").value("sip:1003@pbx.test")).andReturn();
        String listCursor = json.readTree(list.getResponse().getContentAsString()).path("nextCursor").asString();
        read(alice, "conversations?size=1&before=" + listCursor)
                .andExpect(jsonPath("$.content[0].lastMessage.id").value("local-4"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void rejectsInvalidBodiesCursorsAndUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/v1/message-history/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/message-history/messages/id").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(message("accepted")))).andExpect(status().isUnauthorized());
        for (String query : new String[]{"size=0", "size=101", "before=0", "before=bad", "before=9223372036854775808"})
            read(alice, "conversations?" + query).andExpect(status().isBadRequest());
        for (var field : Map.of("body", "", "direction", "invalid", "status", "received", "peer", "invalid", "time", -1L).entrySet()) {
            var bad = message("accepted"); bad.put(field.getKey(), field.getValue());
            save(alice, "bad", bad).andExpect(status().isBadRequest());
        }
        var oversized = message("received"); oversized.put("body", "a".repeat(65536));
        save(alice, "too-long", oversized).andExpect(status().isBadRequest());
        assertThat(history.count()).isZero();
    }
}
