package com.x.backendvoip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP test dependency, shaped from upstream PHP at f32b7a9578e29484671b0e4ab956f7f9300ea1cc. */
final class FusionPbxStubServer implements AutoCloseable {
    static final String DOMAIN_ID = "11111111-1111-1111-1111-111111111111";
    static final String TOKEN = "22222222-2222-2222-2222-222222222222:test-only-rest-secret";
    final Map<String, Map<String, String>> extensions = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile String failingAction;
    volatile int failureStatus;
    volatile boolean malformedCreate;
    volatile boolean conflictCreate;
    volatile long createDelayMillis;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper json = JsonMapper.builder().build();

    FusionPbxStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/app/rest_api/rest.php", this::handle);
            server.setExecutor(executor);
            server.start();
            reset();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/app/rest_api/rest.php"; }

    void reset() {
        extensions.clear();
        extensions.put("1000", Map.of("extension_uuid", "33333333-3333-3333-3333-333333333333", "extension", "1000"));
        calls.clear();
        failingAction = null;
        failureStatus = 0;
        malformedCreate = false;
        conflictCreate = false;
        createDelayMillis = 0;
    }

    int count(String action) { return calls.getOrDefault(action, new AtomicInteger()).get(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String expected = "Basic " + Base64.getEncoder().encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8));
            if (!"POST".equals(exchange.getRequestMethod()) || !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
            String action = body.path("action").asString();
            calls.computeIfAbsent(action, ignored -> new AtomicInteger()).incrementAndGet();
            if (action.equals(failingAction)) {
                send(exchange, failureStatus, Map.of("error", "dependency failure"));
                return;
            }
            if ("domain-details".equals(action)) {
                send(exchange, 200, Map.of("domain_uuid", DOMAIN_ID, "domain_name", "10.155.199.136", "domain_enabled", true));
                return;
            }
            if (!DOMAIN_ID.equals(body.path("domain_uuid").asString())) {
                send(exchange, 404, Map.of("error", "domain not found"));
                return;
            }
            if ("extension-list".equals(action)) {
                send(exchange, 200, extensions.values().stream().map(row -> Map.of(
                        "extension_uuid", row.get("extension_uuid"), "extension", row.get("extension"),
                        "emergency_caller_id_number", row.get("extension"))).toList());
            } else if ("extension-create".equals(action)) {
                String number = body.path("extension").asString();
                var created = Map.of("domain_uuid", DOMAIN_ID, "extension_uuid", UUID.randomUUID().toString(),
                        "extension", number, "password", "remote-only-" + UUID.randomUUID());
                if (conflictCreate || extensions.putIfAbsent(number, created) != null) {
                    send(exchange, 500, Map.of("error", "extension already exists"));
                    return;
                }
                if (createDelayMillis > 0) {
                    try { Thread.sleep(createDelayMillis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                send(exchange, 200, malformedCreate ? Map.of("extension", number) : created);
            } else {
                send(exchange, 501, Map.of("error", "not modeled"));
            }
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
