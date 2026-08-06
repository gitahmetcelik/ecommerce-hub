package com.ecommercehub.connector.mock;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.ConnectorContractTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The concrete first customer of {@link ConnectorContractTest} (Plan Phase 1 gate:
 * "the contract test suite passes"). Every hook below drives mock-pazaryeri's
 * /_admin endpoints — a real channel's contract test would use WireMock or a
 * sandbox account instead, but the inherited @Test methods never change.
 */
class MockPlatformConnectorContractTest extends ConnectorContractTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    static {
        mockPazaryeri.start();
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformConnector connector = new MockPlatformConnector(httpClient, objectMapper);

    private String baseUrl() {
        return "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
    }

    @AfterEach
    void resetAfterEachTest() {
        resetScenarios();
    }

    @Override
    protected PlatformConnector connector() {
        return connector;
    }

    @Override
    protected ChannelConnectionRef connection() {
        return new ChannelConnectionRef(UUID.randomUUID(), UUID.randomUUID(), "MOCK", baseUrl());
    }

    @Override
    protected void resetScenarios() {
        adminPost("/_admin/reset", Map.of());
    }

    @Override
    protected void givenPartialFailureForSkus(Set<String> skus) {
        adminPost("/_admin/scenario", Map.of("failSkus", skus));
    }

    @Override
    protected void givenChannelIsRateLimited() {
        adminPost("/_admin/scenario", Map.of("rateLimitAfter", 0));
    }

    @Override
    protected String signBody(byte[] bodyBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/_admin/sign"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
            return json.get("signature").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String signatureHeaderName() {
        return "X-Mock-Signature";
    }

    @Override
    protected Instant anExistingOrderEventAt() {
        PagedResult<ChannelOrder> firstPage = connector.fetchOrders(connection(), Instant.EPOCH, Page.first(1));
        return firstPage.items().get(0).eventAt();
    }

    @Override
    protected String aNonAsciiTitleFromCatalog() {
        return "Türkçe Ürün İçeriği 😀";
    }

    private void adminPost(String path, Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
