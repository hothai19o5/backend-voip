package com.x.backendvoip;

import com.x.backendvoip.message.HistoryRepository;
import com.x.backendvoip.sip.SipAccount;
import com.x.backendvoip.sip.SipAccountRepository;
import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.user.UserRepository;
import com.x.backendvoip.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired SipAccountRepository sipAccounts;
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
        sipAccounts.findByUserId(alice.getId()).ifPresent(sipAccounts::delete);
        sipAccounts.findByUserId(bob.getId()).ifPresent(sipAccounts::delete);
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

    void mapExtensions() {
        UUID domain = UUID.randomUUID();
        var a = new SipAccount(alice, domain, "PBX.test", "1001", Instant.now());
        var b = new SipAccount(bob, domain, "PBX.test", "1002", Instant.now());
        a.complete(UUID.randomUUID(), "unused", Instant.now());
        b.complete(UUID.randomUUID(), "unused", Instant.now());
        sipAccounts.saveAll(java.util.List.of(a, b));
    }

    ResultActions send(AppUser sender, String id, String peer, String body) throws Exception {
        return mvc.perform(put("/api/v1/message-history/send/{id}", id)
                .with(jwt().jwt(j -> j.subject(sender.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("peer", peer, "body", body))));
    }

    ResultActions upload(String id, String peer, String filename, byte[] bytes) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, "/api/v1/message-history/attachments/{id}", id)
                .file(new MockMultipartFile("file", filename, "text/html", bytes))
                .param("peer", peer)
                .with(jwt().jwt(j -> j.subject(alice.getId().toString()))));
    }

    @Test
    void rejectsEmptyLargeAndExternalAttachmentsWithoutWritingHistory() throws Exception {
        mapExtensions();
        upload("empty", "sip:1002@pbx.test", "empty.txt", new byte[0])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_ATTACHMENT"));
        upload("large", "sip:1002@pbx.test", "large.bin", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
        upload("external", "sip:1003@pbx.test", "example.txt", new byte[] {1})
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SIP_ONLY"));
        assertThat(history.count()).isZero();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_MINIO", matches = "true")
    void storesRealMinioObjectsAndOnlyChatParticipantsCanDownload() throws Exception {
        mapExtensions();
        String peer = "sip:1002@pbx.test";
        byte[] bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD/8AAAAASUVORK5CYII=");
        String filename = "ảnh chia sẻ.png";
        String id = "chat:" + alice.getId() + ":img";
        upload("img", peer, "ignored/" + filename, bytes).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.attachment.filename").value(filename))
                .andExpect(jsonPath("$.attachment.contentType").value("image/png"))
                .andExpect(jsonPath("$.attachment.key").doesNotExist());
        upload("img", peer, filename, bytes).andExpect(status().isOk());
        upload("img", peer, filename, new byte[]{42}).andExpect(status().isConflict());
        send(alice, "img", peer, filename).andExpect(status().isConflict());
        byte[] file = "Xin chào".getBytes(StandardCharsets.UTF_8);
        upload("file", peer, "ghi chú.txt", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.contentType").value("application/octet-stream"));
        assertThat(history.count()).isEqualTo(4);

        read(bob, "updates?after=0").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attachment.filename").value(filename));
        read(bob, "attachments/" + id).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"));
        read(alice, "attachments/chat:" + alice.getId() + ":file")
                .andExpect(status().isOk()).andExpect(content().bytes(file));
        mvc.perform(get("/api/v1/message-history/attachments/{id}", id)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/message-history/attachments/{id}", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void offlineRecipientHasBothMessagesAndCanSyncInOrderWithoutPbx() throws Exception {
        mapExtensions();
        String text = "Bảo ơi, chào bạn 👋";
        String id = "chat:" + alice.getId() + ":request-1";
        send(alice, "request-1", "sips:1002@PBX.test;transport=ws", text)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.account").value("sip:1001@pbx.test"))
                .andExpect(jsonPath("$.peerUserId").value(bob.getId().toString()))
                .andExpect(jsonPath("$.status").value("accepted"));
        send(alice, "request-1", "sip:1002@pbx.test", text).andExpect(status().isOk())
                .andExpect(jsonPath("$.peerUserId").value(bob.getId().toString()));
        send(alice, "request-1", "sip:1002@pbx.test", "Khác").andExpect(status().isConflict());
        send(alice, "request-2", "sip:1002@pbx.test", "Tin thứ hai").andExpect(status().isOk());
        assertThat(history.count()).isEqualTo(4);

        read(bob, "conversations").andExpect(jsonPath("$.content[0].peer").value("sip:1001@pbx.test"))
                .andExpect(jsonPath("$.content[0].name").value("Alice"))
                .andExpect(jsonPath("$.content[0].lastMessage.peerUserId").value(alice.getId().toString()));
        read(bob, "messages?peer=sip:1001@pbx.test").andExpect(jsonPath("$.content.length()").value(2));
        var first = read(bob, "updates?after=0&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].peerUserId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.content[0].direction").value("incoming"))
                .andExpect(jsonPath("$.content[0].status").value("received"))
                .andExpect(jsonPath("$.content[0].body").value(text)).andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).path("nextCursor").asString();
        read(bob, "updates?after=" + cursor).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].body").value("Tin thứ hai"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        read(alice, "updates?after=0").andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].direction").value("outgoing"));
    }

    @Test
    void rejectsUnavailableRecipientsAndPreservesLegacySipHistory() throws Exception {
        mapExtensions();
        bob.changeStatus(UserStatus.BLOCKED);
        users.save(bob);
        send(alice, "blocked", "sip:1002@pbx.test", "Hello")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_UNAVAILABLE"));
        send(alice, "external", "sip:1999@pbx.test", "Hello")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SIP_ONLY"));
        send(alice, "self", "sip:1001@pbx.test", "Hello").andExpect(status().isBadRequest());
        send(alice, "empty", "sip:1002@pbx.test", " ").andExpect(status().isBadRequest());
        save(alice, "chat:reserved", message("accepted")).andExpect(status().isBadRequest());
        assertThat(history.count()).isZero();
        save(alice, "existing-sip-id", message("accepted")).andExpect(status().isOk());
        read(bob, "updates?after=0").andExpect(jsonPath("$.content").isEmpty());
        read(alice, "updates?after=-1").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/message-history/updates?after=0")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRetriesAndReverseSendsProduceOneCopyPerAccount() throws Exception {
        mapExtensions();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> {
                start.await();
                send(alice, "same", "sip:1002@pbx.test", "Forward").andExpect(status().isOk());
                return null;
            });
            var retry = executor.submit(() -> {
                start.await();
                send(alice, "same", "sip:1002@pbx.test", "Forward").andExpect(status().isOk());
                return null;
            });
            var reverse = executor.submit(() -> {
                start.await();
                send(bob, "same", "sip:1001@pbx.test", "Reverse").andExpect(status().isOk());
                return null;
            });
            start.countDown();
            for (var result : java.util.List.of(first, retry, reverse)) result.get(15, TimeUnit.SECONDS);
        }
        assertThat(history.count()).isEqualTo(4);
        read(bob, "updates?after=0").andExpect(jsonPath("$.content.length()").value(2));
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
                .andExpect(jsonPath("$.content[0].peerUserId").isEmpty())
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
