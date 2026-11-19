package com.x.backendvoip;

import com.x.backendvoip.auth.AuthSessionRepository;
import com.x.backendvoip.user.UserRepository;
import com.x.backendvoip.user.UserStatus;
import com.x.backendvoip.user.AppUser;
import com.x.backendvoip.sip.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthUsersIntegrationTests {
    static final FusionPbxStubServer pbx = new FusionPbxStubServer();

    @DynamicPropertySource
    static void fusionPbxProperties(DynamicPropertyRegistry registry) {
        registry.add("app.fusionpbx.api-url", pbx::url);
        registry.add("app.fusionpbx.api-token", () -> FusionPbxStubServer.TOKEN);
        registry.add("app.fusionpbx.credentials-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("app.fusionpbx.request-timeout", () -> "500ms");
        registry.add("app.fusionpbx.domain-name", () -> "10.155.199.136");
        registry.add("app.fusionpbx.server-ip", () -> "10.155.199.136");
        registry.add("app.fusionpbx.wss-url", () -> "wss://10.155.199.136:7443");
        registry.add("app.fusionpbx.extension-start", () -> "1001");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:5173,http://127.0.0.1:5173");
    }

    @AfterAll
    static void stopPbx() { pbx.close(); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired SipAccountRepository sipAccounts;
    @Autowired FusionPbxProperties pbxProperties;
    @Autowired JdbcTemplate jdbc;
    private final AtomicInteger fixtureNumber = new AtomicInteger();

    @BeforeEach
    void resetDatabase() {
        sessions.deleteAllInBatch();
        sipAccounts.deleteAllInBatch();
        users.deleteAllInBatch();
        pbx.reset();
        pbxProperties.setApiToken(FusionPbxStubServer.TOKEN);
    }

    @Test
    void registerNormalizesIdentityHashesPasswordAndDoesNotAutoLogin() throws Exception {
        String body = json.writeValueAsString(Map.of("username", " Alice ", "email", " Alice@Example.com ",
                "password", "  mật-khẩu-🔐  ", "displayName", " Alice Nguyen ", "phoneNumber", "090 000 0001"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice Nguyen"))
                .andExpect(jsonPath("$.phoneNumber").value("+84900000001"))
                .andExpect(jsonPath("$.role").value("USER")).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").isNotEmpty()).andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        var user = users.findByUsername("alice").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo("  mật-khẩu-🔐  ");
        assertThat(passwords.matches("  mật-khẩu-🔐  ", user.getPasswordHash())).isTrue();
        assertThat(sessions.count()).isZero();
        var mapping = sipAccounts.findByUserId(user.getId()).orElseThrow();
        assertThat(mapping.getExtension()).isEqualTo("1001");
        assertThat(mapping.getProvisioningStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(mapping.getEncryptedPassword()).doesNotContain(pbx.extensions.get("1001").get("password"));
        assertThat(pbx.count("extension-create")).isEqualTo(1);
    }

    @Test
    void duplicateUsernameAndEmailAreCaseInsensitive() throws Exception {
        register("alice", "alice@example.com", "Alice");
        registration("ALICE", "different@example.com", "Alice").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        registration("other", "ALICE@EXAMPLE.COM", "Other").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void concurrentlyRegisteringEquivalentPhoneNumbersCreatesOneExtension() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> registration("alice", "alice@example.com", "Alice", "090 000 0001")
                    .andReturn().getResponse().getStatus();
            Callable<Integer> second = () -> registration("bob", "bob@example.com", "Bob", "+84900000001")
                    .andReturn().getResponse().getStatus();
            var result = pool.invokeAll(List.of(first, second));
            assertThat(List.of(result.get(0).get(), result.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }
        registration("carol", "carol@example.com", "Carol", "+84 (90) 000 0001").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_IN_USE"));
        assertThat(pbx.count("extension-create")).isEqualTo(1);
    }

    @Test
    void invalidRegistrationReturnsFieldErrorsWithoutEchoingSecrets() throws Exception {
        String body = json.writeValueAsString(Map.of("username", "a!", "email", "wrong", "password", "short", "displayName", " "));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.username").exists()).andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists()).andExpect(jsonPath("$.errors.displayName").exists())
                .andExpect(jsonPath("$.errors.phoneNumber").exists())
                .andExpect(content().string(not(containsString("short"))));
        assertThat(users.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "status", "id"})
    void registrationRejectsPrivilegedOrUnknownFields(String field) throws Exception {
        String body = "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"password-123\",\"displayName\":\"Alice\",\"" + field + "\":\"ADMIN\"}";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(users.count()).isZero();
    }

    @Test
    void missingAndMalformedBodiesAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":null,\"password\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsUsableTokenAndMeReturnsOnlyOwnProfile() throws Exception {
        register("alice", "alice@example.com", "Alice");
        var result = login(" ALICE ", "password-123").andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).path("accessToken").asString();
        var claims = decoder.decode(token);
        assertThat(claims.getAudience()).containsExactly("alohub-api");
        assertThat(claims.getExpiresAt()).isAfter(Instant.now().plusSeconds(7 * 3600));
        assertThat(sessions.count()).isEqualTo(1);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void wrongUnknownAndBlockedLoginsShareTheSameError() throws Exception {
        register("alice", "alice@example.com", "Alice");
        for (String name : List.of("alice", "unknown")) {
            login(name, "wrong-password").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Username or password is incorrect."));
        }
        blockAlice();
        login("alice", "password-123").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Username or password is incorrect."));
        assertThat(sessions.count()).isZero();
    }

    @Test
    void protectedEndpointsRequireBearerAuthentication() throws Exception {
        for (String path : List.of("/api/v1/users", "/api/v1/users/me", "/api/v1/users/contact-phone")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"alohub\""));
        }
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Basic YWxpY2U6cGFzc3dvcmQ="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contactPhoneUsesLocalMappingAndHidesNumbersForUnavailableContacts() throws Exception {
        var registration = registration("alice", "alice@example.com", "Alice", "090 000 0001")
                .andExpect(status().isCreated()).andReturn();
        registration("bob", "bob@example.com", "Bob", "090 000 0002")
                .andExpect(status().isCreated());
        var sip = json.readTree(registration.getResponse().getContentAsString()).path("sip");
        String peer = "sip:" + sip.path("extension").asString() + "@" + sip.path("domain").asString();
        String auth = bearer(token("bob"));
        String path = "/api/v1/users/contact-phone";
        int pbxReads = pbx.count("extension-list");
        mvc.perform(get(path).param("peer", peer).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.phoneNumber").value("+84900000001"));
        mvc.perform(get(path).param("peer", "sip:1234@external.test").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(content().json("{\"phoneNumber\":null}"));
        mvc.perform(get(path).param("peer", "invalid").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        blockAlice();
        mvc.perform(get(path).param("peer", peer).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(content().json("{\"phoneNumber\":null}"));
        assertThat(pbx.count("extension-list")).isEqualTo(pbxReads);
    }

    @Test
    void presenceTracksHeartbeatsPerSessionThroughLogoutAndTimeout() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("bob", "bob@example.com", "Bob");
        register("carol", "carol@example.com", "Carol");
        String alice = token("alice");
        String bob = token("bob");
        String bobSecond = token("bob");
        String bobId = decoder.decode(bob).getSubject();
        String aliceId = decoder.decode(alice).getSubject();

        mvc.perform(post("/api/v1/presence/heartbeat")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.content[*].username", contains("bob", "carol")))
                .andExpect(jsonPath("$.content[*].online", contains(false, false)));
        int pbxReads = pbx.count("extension-list");
        heartbeat(bob).andExpect(jsonPath("$.onlineUserIds", contains(bobId)))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        heartbeat(bobSecond).andExpect(jsonPath("$.onlineUserIds", contains(bobId)));
        assertThat(pbx.count("extension-list")).isEqualTo(pbxReads);

        mvc.perform(get("/api/v1/users").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.content[*].online", contains(true, false)));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", bearer(bob)))
                .andExpect(status().isUnauthorized());
        heartbeat(alice).andExpect(jsonPath("$.onlineUserIds", containsInAnyOrder(aliceId, bobId)));

        jdbc.update("update auth_sessions set presence_until = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), java.util.UUID.fromString(decoder.decode(bobSecond).getId()));
        heartbeat(alice).andExpect(jsonPath("$.onlineUserIds", contains(aliceId)));
        heartbeat(bobSecond).andExpect(jsonPath("$.onlineUserIds", containsInAnyOrder(aliceId, bobId)));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(bobSecond)))
                .andExpect(status().isNoContent());
        heartbeat(alice).andExpect(jsonPath("$.onlineUserIds", contains(aliceId)));
    }

    @Test
    void presenceDropsBlockedAndExpiredSessionsEvenWithRecentHeartbeats() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("bob", "bob@example.com", "Bob");
        register("carol", "carol@example.com", "Carol");
        String alice = token("alice");
        String bob = token("bob");
        String carol = token("carol");
        heartbeat(alice);
        heartbeat(bob);
        var bobSession = decoder.decode(bob);
        jdbc.update("update auth_sessions set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), java.util.UUID.fromString(bobSession.getId()));
        blockAlice();
        heartbeat(carol).andExpect(jsonPath("$.onlineUserIds", contains(decoder.decode(carol).getSubject())));
    }

    private ResultActions heartbeat(String token) throws Exception {
        return mvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void malformedTamperedAndExpiredTokensAreRejected() throws Exception {
        register("alice", "alice@example.com", "Alice");
        String token = token("alice");
        int signatureOffset = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureOffset) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, signatureOffset) + replacement + token.substring(signatureOffset + 1);
        var original = decoder.decode(token);
        String expired = signedToken(original, "alohub-api", Instant.now().minusSeconds(120), original.getId());
        String wrongAudience = signedToken(original, "another-api", Instant.now().plusSeconds(3600), original.getId());
        String noSession = signedToken(original, "alohub-api", Instant.now().plusSeconds(3600), java.util.UUID.randomUUID().toString());
        for (String invalid : List.of("not-a-jwt", tampered, expired, wrongAudience, noSession)) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(invalid)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void blockTakesEffectOnAlreadyIssuedToken() throws Exception {
        register("alice", "alice@example.com", "Alice");
        String token = token("alice");
        blockAlice();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyCurrentSessionImmediately() throws Exception {
        register("alice", "alice@example.com", "Alice");
        String first = token("alice");
        String second = token("alice");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(first)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(first)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(first)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(second)))
                .andExpect(status().isOk());
        assertThat(sessions.findAll().stream().filter(s -> s.getRevokedAt() != null).count()).isEqualTo(1);
    }

    @Test
    void directoryIncludesAccountsWithoutSessionsExcludesSelfAndPaginatesStably() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("charlie", "charlie@example.com", "Charlie");
        register("bob", "bob@example.com", "Bob");
        String token = token("alice");
        mvc.perform(get("/api/v1/users").param("size", "1").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].username").value("bob"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/users").param("size", "1").param("page", "1").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].username").value("charlie"))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/users").param("page", "99").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void searchIsCaseInsensitiveAndTreatsSqlWildcardsLiterally() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("bob", "bob@example.com", "Bob 100%_\\");
        register("charlie", "charlie@example.com", "Charlie");
        String token = token("alice");
        for (String search : List.of("BOB", "  bob  ", "%", "_", "\\", "100%_\\")) {
            mvc.perform(get("/api/v1/users").param("q", search).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].username").value("bob"));
        }
        mvc.perform(get("/api/v1/users").param("q", "Alice").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=nope", "page=2147483647", "size=2147483648"})
    void invalidPaginationIsRejected(String query) throws Exception {
        register("alice", "alice@example.com", "Alice");
        mvc.perform(get("/api/v1/users?" + query).header("Authorization", bearer(token("alice"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void oversizedSearchIsRejected() throws Exception {
        register("alice", "alice@example.com", "Alice");
        mvc.perform(get("/api/v1/users").param("q", "x".repeat(101)).header("Authorization", bearer(token("alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsConfiguredFrontendAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/v1/users").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/v1/users").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void concurrentDuplicateRegistrationCreatesExactlyOneAccount() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> registration("alice", "alice@example.com", "Alice")
                    .andReturn().getResponse().getStatus();
            var results = pool.invokeAll(List.of(request, request));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void healthIsPublicAndDoesNotExposeDatabaseDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void directoryReadsPbxAndMarksAnExternallyRemovedExtensionAbsent() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("bob", "bob@example.com", "Bob");
        String token = token("alice");
        var bob = users.findByUsername("bob").orElseThrow();
        var account = sipAccounts.findByUserId(bob.getId()).orElseThrow();
        int before = pbx.count("extension-list");
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].sip.extensionPresent").value(true))
                .andExpect(jsonPath("$.content[0].sip.password").doesNotExist());
        assertThat(pbx.count("extension-list")).isEqualTo(before + 1);
        pbx.extensions.remove(account.getExtension());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].sip.extensionPresent").value(false));
        // An extension number reused under another UUID must not be attached to Bob.
        pbx.extensions.put(account.getExtension(), Map.of("extension", account.getExtension(), "extension_uuid", java.util.UUID.randomUUID().toString()));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].sip.extensionPresent").value(false));
    }

    @Test
    void upstreamListFailureDoesNotReturnAnApparentlyHealthyLocalDirectory() throws Exception {
        register("alice", "alice@example.com", "Alice");
        String token = token("alice");
        pbx.failingAction = "extension-list";
        pbx.failureStatus = 503;
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("TELEPHONY_REJECTED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isBadGateway());
    }

    @Test
    void missingConfigurationAndPreflightFailureDoNotCreateLocalUsers() throws Exception {
        pbxProperties.setApiToken("");
        registration("alice", "alice@example.com", "Alice").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TELEPHONY_NOT_CONFIGURED"));
        assertThat(users.count()).isZero();
        pbxProperties.setApiToken(FusionPbxStubServer.TOKEN);
        pbx.failingAction = "domain-details";
        pbx.failureStatus = 401;
        registration("alice", "alice@example.com", "Alice").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TELEPHONY_AUTH_FAILED"));
        assertThat(users.count()).isZero();
        assertThat(pbx.count("extension-create")).isZero();
    }

    @Test
    void createTimeoutRetainsUnknownReservationAndDoesNotRetryTheWrite() throws Exception {
        pbx.createDelayMillis = 1000;
        registration("alice", "alice@example.com", "Alice").andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("TELEPHONY_TIMEOUT"));
        var user = users.findByUsername("alice").orElseThrow();
        assertThat(sipAccounts.findByUserId(user.getId()).orElseThrow().getProvisioningStatus()).isEqualTo(ProvisioningStatus.UNKNOWN);
        assertThat(pbx.extensions).containsKey("1001");
        registration("alice", "alice@example.com", "Alice").andExpect(status().isConflict());
        login("alice", "password-123").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVISIONING_PENDING"));
        assertThat(pbx.count("extension-create")).isEqualTo(1);
        assertThat(sessions.count()).isZero();
    }

    @Test
    void malformedCreateResponseDoesNotReportRegistrationSuccess() throws Exception {
        pbx.malformedCreate = true;
        registration("alice", "alice@example.com", "Alice").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TELEPHONY_INVALID_RESPONSE"));
        assertThat(sipAccounts.findAll().getFirst().getProvisioningStatus()).isEqualTo(ProvisioningStatus.UNKNOWN);
        assertThat(pbx.count("extension-create")).isEqualTo(1);
    }

    @Test
    void externalAllocationConflictIsPreservedAsFailedWithoutAdoptingThatExtension() throws Exception {
        pbx.conflictCreate = true;
        registration("alice", "alice@example.com", "Alice").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXTENSION_CONFLICT"));
        var account = sipAccounts.findAll().getFirst();
        assertThat(account.getProvisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(account.getExtensionId()).isNull();
        assertThat(account.getEncryptedPassword()).isNull();
    }

    @Test
    void ownSipCredentialsAreDecryptedOnlyForTheAuthenticatedCaller() throws Exception {
        register("alice", "alice@example.com", "Alice");
        register("bob", "bob@example.com", "Bob");
        String token = token("alice");
        var account = sipAccounts.findByUserId(users.findByUsername("alice").orElseThrow().getId()).orElseThrow();
        mvc.perform(get("/api/v1/sip/account")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sip/account").param("userId", users.findByUsername("bob").orElseThrow().getId().toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.extension").value(account.getExtension()))
                .andExpect(jsonPath("$.password").value(pbx.extensions.get(account.getExtension()).get("password")))
                .andExpect(jsonPath("$.domain").value("10.155.199.136"))
                .andExpect(jsonPath("$.wssUrl").value("wss://10.155.199.136:7443"))
                .andExpect(jsonPath("$.apiToken").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        pbx.extensions.remove(account.getExtension());
        mvc.perform(get("/api/v1/sip/account").header("Authorization", bearer(token)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void legacyUserIsProvisionedAtFirstSuccessfulLoginOnly() throws Exception {
        users.saveAndFlush(new AppUser("legacy", "legacy@example.com", passwords.encode("password-123"), "Legacy", Instant.now()));
        login("legacy", "incorrect").andExpect(status().isUnauthorized());
        assertThat(pbx.count("extension-create")).isZero();
        login("legacy", "password-123").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.sip.provisioningStatus").value("READY"));
        login("legacy", "password-123").andExpect(status().isOk());
        assertThat(pbx.count("extension-create")).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRegistrationsReserveDifferentExtensions() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> registration("alice", "alice@example.com", "Alice").andReturn().getResponse().getStatus();
            Callable<Integer> second = () -> registration("bob", "bob@example.com", "Bob").andReturn().getResponse().getStatus();
            var results = pool.invokeAll(List.of(first, second));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsOnly(201);
        }
        assertThat(sipAccounts.findAll().stream().map(SipAccount::getExtension).toList()).containsExactlyInAnyOrder("1001", "1002");
        assertThat(pbx.count("extension-create")).isEqualTo(2);
    }

    private ResultActions registration(String username, String email, String displayName) throws Exception {
        return registration(username, email, displayName, "+8490000%04d".formatted(fixtureNumber.incrementAndGet()));
    }

    private ResultActions registration(String username, String email, String displayName, String phone) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("username", username, "email", email, "displayName", displayName, "password", "password-123", "phoneNumber", phone))));
    }

    private void register(String username, String email, String displayName) throws Exception {
        registration(username, email, displayName).andExpect(status().isCreated());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String token(String username) throws Exception {
        JsonNode response = json.readTree(login(username, "password-123").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.path("accessToken").asString();
    }

    private String bearer(String token) { return "Bearer " + token; }

    private void blockAlice() {
        var alice = users.findByUsername("alice").orElseThrow();
        alice.changeStatus(UserStatus.BLOCKED);
        users.saveAndFlush(alice);
    }

    private String signedToken(Jwt original, String audience, Instant expiry, String sessionId) {
        var claims = JwtClaimsSet.builder().issuer(original.getClaimAsString("iss")).subject(original.getSubject())
                .audience(List.of(audience)).id(sessionId).issuedAt(Instant.now().minusSeconds(300))
                .expiresAt(expiry).claim("role", "USER").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
