package com.x.backendvoip;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.x.backendvoip.sip.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class FusionPbxTlsTests {
    private static HttpsServer server;
    private static ExecutorService executor;
    private static KeyStore serverKeys;
    private static URI apiUrl;
    private static final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    static void startSelfSignedServer() throws Exception {
        serverKeys = KeyStore.getInstance("PKCS12");
        try (var resource = FusionPbxTlsTests.class.getResourceAsStream("/tls/selfsigned-wrong-host.p12")) {
            serverKeys.load(resource, "test-only-password".toCharArray());
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(serverKeys, "test-only-password".toCharArray());
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/app/rest_api/rest.php", exchange -> {
            try (exchange) {
                String expected = "Basic " + Base64.getEncoder().encodeToString(FusionPbxStubServer.TOKEN.getBytes(StandardCharsets.UTF_8));
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
                var request = json.readTree(exchange.getRequestBody().readAllBytes());
                Object body = "domain-details".equals(request.path("action").asString())
                        ? Map.of("domain_uuid", FusionPbxStubServer.DOMAIN_ID, "domain_name", "10.155.199.136", "domain_enabled", true)
                        : java.util.List.of();
                byte[] bytes = json.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        // Certificate SAN is fusionpbx-test.invalid, deliberately not this loopback IP.
        apiUrl = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/app/rest_api/rest.php");
    }

    @AfterAll
    static void stopSelfSignedServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.close();
    }

    @Test
    void verificationIsEnabledByDefaultAndRejectsSelfSignedCertificates() {
        var properties = properties();
        assertThat(properties.isVerifySsl()).isTrue();
        var client = new FusionPbxClient(properties, json);
        assertThatThrownBy(client::snapshot).isInstanceOfSatisfying(FusionPbxException.class,
                error -> assertThat(error.getCode()).isEqualTo("TELEPHONY_UNAVAILABLE"));
    }

    @Test
    void optOutAcceptsSelfSignedAndMismatchedHostnameWithoutChangingJvmDefaults() throws Exception {
        SSLContext originalContext = SSLContext.getDefault();
        String originalHostnameSetting = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
        var properties = properties();
        properties.setVerifySsl(false);
        var client = new FusionPbxClient(properties, json);

        var result = client.snapshot();
        assertThat(result.domainId().toString()).isEqualTo(FusionPbxStubServer.DOMAIN_ID);
        assertThat(result.extensions()).isEmpty();
        assertThat(SSLContext.getDefault()).isSameAs(originalContext);
        assertThat(System.getProperty("jdk.internal.httpclient.disableHostnameVerification")).isEqualTo(originalHostnameSetting);

        // A newly built verified PBX client still rejects the same certificate.
        assertThatThrownBy(new FusionPbxClient(properties(), json)::snapshot).isInstanceOf(FusionPbxException.class);
    }

    @Test
    void ordinaryHttpsClientsStillCheckHostnameEvenWhenCertificateIsTrusted() throws Exception {
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(serverKeys);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        try (var ordinaryClient = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(3)).build()) {
            var request = HttpRequest.newBuilder(apiUrl).timeout(Duration.ofSeconds(3)).GET().build();
            assertThatThrownBy(() -> ordinaryClient.send(request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }

    private static FusionPbxProperties properties() {
        var properties = new FusionPbxProperties();
        properties.setApiUrl(apiUrl);
        properties.setApiToken(FusionPbxStubServer.TOKEN);
        properties.setDomainName("10.155.199.136");
        properties.setExtensionStart(1000);
        properties.setExtensionEnd(9999);
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setRequestTimeout(Duration.ofSeconds(3));
        return properties;
    }
}
