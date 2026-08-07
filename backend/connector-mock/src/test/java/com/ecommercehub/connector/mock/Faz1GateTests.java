package com.ecommercehub.connector.mock;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelItemRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.ChannelRateLimitedException;
import com.ecommercehub.connector.ItemResult;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.connector.StockUpdate;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.InMemoryRateLimitBudget;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The literal Phase 1 gate criteria from the plan, as one test class — everything
 * these exercise is also covered more generally by {@link MockPlatformConnectorContractTest},
 * but the plan calls these specific scenarios out by name, so they're worth pinning
 * down exactly as described rather than trusting they fall out of the generic suite.
 */
class Faz1GateTests {

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
    private final ChannelConnectionRef connection = new ChannelConnectionRef(
            UUID.randomUUID(), UUID.randomUUID(), "MOCK", baseUrl());

    private String baseUrl() {
        return "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
    }

    @BeforeEach
    @AfterEach
    void resetScenarios() throws Exception {
        adminPost("/_admin/reset", Map.of());
    }

    private void adminPost(String path, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Test
    @DisplayName("Phase 1 gate: 50 seeded orders are fetched across pages and normalized without gaps or duplicates")
    void fetchesAllFiftySeedOrdersAcrossPages() {
        List<ChannelOrder> all = new ArrayList<>();
        Page page = Page.first(7); // an odd page size on purpose — must not divide 50 evenly

        while (true) {
            PagedResult<ChannelOrder> result = connector.fetchOrders(connection, Instant.EPOCH, page);
            all.addAll(result.items());
            if (!result.hasMore()) {
                break;
            }
            page = page.next();
        }

        Set<String> uniqueIds = new HashSet<>();
        for (ChannelOrder order : all) {
            uniqueIds.add(order.channelOrderId());
            assertThat(order.customerOrderNumber()).isNotBlank();
            assertThat(order.eventAt()).isNotNull();
            assertThat(order.items()).isNotEmpty();
        }

        assertThat(all).hasSize(50);
        assertThat(uniqueIds).hasSize(50);
    }

    @Test
    @DisplayName("Phase 1 gate: a bulk stock push with 3 failing items reports per-item results correctly, not one big failure")
    void bulkStockPushWithThreeFailingItemsReportsPerItemResults() throws Exception {
        adminPost("/_admin/scenario", Map.of("failSkus", List.of("SKU-1", "SKU-3", "SKU-5")));

        List<StockUpdate> batch = List.of(
                new StockUpdate(new ChannelItemRef("SKU-0", "SKU-0", null), 10),
                new StockUpdate(new ChannelItemRef("SKU-1", "SKU-1", null), 10),
                new StockUpdate(new ChannelItemRef("SKU-2", "SKU-2", null), 10),
                new StockUpdate(new ChannelItemRef("SKU-3", "SKU-3", null), 10),
                new StockUpdate(new ChannelItemRef("SKU-4", "SKU-4", null), 10),
                new StockUpdate(new ChannelItemRef("SKU-5", "SKU-5", null), 10));

        List<ItemResult> results = connector.updateStock(connection, batch);

        assertThat(results).hasSize(6);
        Set<String> failedSkus = new HashSet<>();
        for (ItemResult result : results) {
            if (!result.success()) {
                failedSkus.add(result.referenceId());
                assertThat(result.error()).isNotBlank();
            }
        }
        assertThat(failedSkus).containsExactlyInAnyOrder("SKU-1", "SKU-3", "SKU-5");
    }

    @Test
    @DisplayName("Phase 1 gate: the same intent calling createShipment twice creates exactly one shipment in the channel")
    void repeatedShipmentIntentCreatesExactlyOneShipment() {
        CallIntentRef intent = new CallIntentRef(UUID.randomUUID(), UUID.randomUUID().toString());
        ShipmentRequest request = new ShipmentRequest("order-0");

        ShipmentResult first = connector.createShipment(connection, request, intent);
        ShipmentResult second = connector.createShipment(connection, request, intent);

        assertThat(second.channelShipmentId()).isEqualTo(first.channelShipmentId());
        assertThat(second.trackingNumber()).isEqualTo(first.trackingNumber());
    }

    @Test
    @DisplayName("Phase 1 gate: on 429, BACKGROUND backs off while INTERACTIVE keeps working against the budget")
    void rateLimitBacksOffBackgroundWithoutAffectingInteractive() throws Exception {
        RateLimitBudget budget = new InMemoryRateLimitBudget(100);

        // BACKGROUND makes a call, the channel 429s it — this is the real HTTP round trip,
        // not a simulated exception.
        adminPost("/_admin/scenario", Map.of("rateLimitAfter", 0));
        assertThat(budget.tryAcquire(BudgetClass.BACKGROUND)).isTrue(); // budget itself doesn't know about the 429 yet

        assertThatThrownBy(() -> connector.fetchOrders(connection, Instant.EPOCH, Page.first(1)))
                .isInstanceOf(ChannelRateLimitedException.class);

        // The caller reports the 429 back to the budget — BACKGROUND backs off...
        budget.reportRateLimited(BudgetClass.BACKGROUND, java.time.Duration.ofMinutes(1));
        assertThat(budget.tryAcquire(BudgetClass.BACKGROUND))
                .withFailMessage("BACKGROUND must refuse further acquisitions while backed off")
                .isFalse();

        // ...but INTERACTIVE is completely unaffected and can still call the channel.
        assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE)).isTrue();
        adminPost("/_admin/reset", Map.of()); // lift the channel-side 429 for the actual interactive call
        PagedResult<ChannelOrder> result = connector.fetchOrders(connection, Instant.EPOCH, Page.first(1));
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Phase 1 gate: the mock connector's declared capability matrix is filled in, not empty")
    void capabilityMatrixIsFilledIn() {
        Set<Capability> capabilities = connector.capabilities();

        assertThat(capabilities).isNotEmpty();
        assertThat(capabilities).contains(
                Capability.FETCH_ORDERS, Capability.FETCH_CATALOG,
                Capability.STOCK_PUSH, Capability.PRICE_PUSH,
                Capability.SHIPMENT_CREATE, Capability.RETURN_DECISION_SUBMIT);
    }
}
