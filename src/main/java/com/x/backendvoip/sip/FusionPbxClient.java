package com.x.backendvoip.sip;

import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Socket;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

@Component
public class FusionPbxClient {
    private static final Logger log = LoggerFactory.getLogger(FusionPbxClient.class);
    private final FusionPbxProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public FusionPbxClient(FusionPbxProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        // No redirects: the administrative Authorization header must stay on this endpoint.
        var builder = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        if (!properties.isVerifySsl() && "https".equalsIgnoreCase(properties.getApiUrl().getScheme())) {
            builder.sslContext(insecureSslContext());
            log.warn("FusionPBX REST certificate and hostname verification are disabled. Use this option only for a trusted development server.");
        }
        this.http = builder.build();
    }

    private static SSLContext insecureSslContext() {
        try {
            var context = SSLContext.getInstance("TLS");
            // Extended overloads deliberately bypass both chain and endpoint identity checks.
            // This context belongs only to this client: no JVM properties/defaults are changed.
            var trustManager = new X509ExtendedTrustManager() {
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot initialize FusionPBX TLS client.", error);
        }
    }

    public PbxSnapshot snapshot() {
        properties.requireApiConfiguration();
        var domain = call(Map.of("action", "domain-details", "domain_name", properties.getDomainName()));
        UUID domainId = uuid(domain, "domain_uuid");
        if (!properties.getDomainName().equals(text(domain, "domain_name"))) throw invalidResponse();
        String enabled = domain.path("domain_enabled").asString();
        if (!"true".equalsIgnoreCase(enabled)) {
            throw new FusionPbxException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "Account calling service is unavailable.", true);
        }
        var extensions = call(Map.of("action", "extension-list", "domain_uuid", domainId.toString()));
        if (!extensions.isArray()) throw invalidResponse();
        var result = new ArrayList<PbxExtension>();
        for (JsonNode extension : extensions) {
            result.add(new PbxExtension(uuid(extension, "extension_uuid"), text(extension, "extension")));
        }
        return new PbxSnapshot(domainId, List.copyOf(result));
    }

    public CreatedExtension createExtension(UUID domainId, String number, String displayName) {
        var response = call(Map.of("action", "extension-create", "domain_uuid", domainId.toString(),
                "extension", number, "caller_id_name", displayName, "caller_id_number", number));
        UUID id = uuid(response, "extension_uuid");
        if (!domainId.equals(uuid(response, "domain_uuid")) || !number.equals(text(response, "extension"))) throw invalidResponse();
        return new CreatedExtension(id, number, text(response, "password"));
    }

    private JsonNode call(Map<String, String> payload) {
        properties.requireApiConfiguration();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(properties.getApiToken().getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(properties.getApiUrl()).timeout(properties.getRequestTimeout())
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new FusionPbxException(HttpStatus.BAD_GATEWAY, "TELEPHONY_AUTH_FAILED", "Account calling service authentication failed.", true);
            }
            JsonNode result;
            try {
                result = json.readTree(response.body());
            } catch (RuntimeException error) {
                throw invalidResponse();
            }
            if (result == null || (!result.isObject() && !result.isArray())) throw invalidResponse();
            if (result.hasNonNull("error")) {
                if ("extension-create".equals(payload.get("action")) && "extension already exists".equals(result.path("error").asString())) {
                    throw new FusionPbxException(HttpStatus.CONFLICT, "EXTENSION_CONFLICT", "Account calling setup encountered a conflict. Contact the administrator.", true);
                }
                boolean definitive = response.statusCode() >= 400 && response.statusCode() < 500;
                throw new FusionPbxException(HttpStatus.BAD_GATEWAY, "TELEPHONY_REJECTED", "Account calling service rejected the request.", definitive);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new FusionPbxException(HttpStatus.BAD_GATEWAY, "TELEPHONY_REJECTED", "Account calling service returned an unsuccessful response.", false);
            }
            return result;
        } catch (HttpTimeoutException error) {
            throw new FusionPbxException(HttpStatus.GATEWAY_TIMEOUT, "TELEPHONY_TIMEOUT", "Account calling service timed out.", false);
        } catch (IOException error) {
            // Do not expose upstream response bodies, credentials, URLs or exception messages.
            throw new FusionPbxException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "Account calling service could not be reached.", false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FusionPbxException(HttpStatus.SERVICE_UNAVAILABLE, "TELEPHONY_UNAVAILABLE", "Account calling service request was interrupted.", false);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw invalidResponse();
        return value.asString();
    }

    private UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException error) {
            throw invalidResponse();
        }
    }

    private FusionPbxException invalidResponse() {
        return new FusionPbxException(HttpStatus.BAD_GATEWAY, "TELEPHONY_INVALID_RESPONSE", "Account calling service returned an invalid response.", false);
    }
}
