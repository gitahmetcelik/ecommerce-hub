package com.ecommercehub.connector.mock;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.CallStatus;
import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelItemRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.ChannelOrderItem;
import com.ecommercehub.connector.ChannelProduct;
import com.ecommercehub.connector.ChannelRateLimitedException;
import com.ecommercehub.connector.ChannelReturn;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.connector.ItemResult;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.PriceUpdate;
import com.ecommercehub.connector.RawRequest;
import com.ecommercehub.connector.RefundRequest;
import com.ecommercehub.connector.RefundResult;
import com.ecommercehub.connector.ReturnDecision;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.connector.SignatureVerification;
import com.ecommercehub.connector.StockUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A second marketplace shape, deliberately unlike the first one.
 *
 * <p>What a second real integration would actually confront the hub with is not another
 * API to call but a different <em>shape</em>: this channel identifies everything by
 * barcode and has no concept of a seller SKU, it never pushes webhooks, and it does not
 * honour a client-supplied idempotency key. Its capability set says all three, and the
 * point of the capability matrix (Plan §8) is that none of it reaches the domain as a
 * special case.
 *
 * <p>Each missing capability changes a real behaviour rather than a label:
 * <ul>
 *   <li>no {@link Capability#WEBHOOK} — orders can only ever arrive through the
 *       reconcile poll, so a channel like this makes the polling path load-bearing
 *       rather than a fallback nobody exercises</li>
 *   <li>no {@link Capability#REQUEST_IDEMPOTENCY_KEY} — repeating a shipment request
 *       genuinely creates a second label at this channel, so recovery has to go
 *       through {@link #queryCallStatus} and never through a retry</li>
 * </ul>
 */
public class MockBarcodeMarketplaceConnector implements PlatformConnector {

    public static final String CHANNEL_TYPE = "MOCK_BARCODE";

    private static final Set<Capability> CAPABILITIES = EnumSet.of(
            Capability.FETCH_ORDERS, Capability.FETCH_CATALOG, Capability.STOCK_PUSH, Capability.PRICE_PUSH,
            Capability.SHIPMENT_CREATE);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MockBarcodeMarketplaceConnector(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * Products carry a barcode and a title, and nothing else identifying.
     *
     * <p>{@code sku} comes back null on purpose. The hub's SKU is the hub's own key
     * (Plan §3), and a channel that does not have one cannot be made to invent one —
     * the catalogue import is what has to cope.
     */
    @Override
    public PagedResult<ChannelProduct> fetchCatalog(ChannelConnectionRef connection, Page page) {
        JsonNode json = get(connection, "/v2/catalog?page=" + page.pageNumber() + "&pageSize=" + page.pageSize());

        List<ChannelProduct> products = new ArrayList<>();
        for (JsonNode p : json.get("items")) {
            String barcode = p.get("barcode").asText();
            JsonNode stock = p.get("stock");

            products.add(new ChannelProduct(barcode, barcode, null, barcode, p.get("title").asText(),
                    stock == null || stock.isNull() ? null : stock.asInt()));
        }
        return toPagedResult(json, products);
    }

    /** Order lines are identified by barcode too — there is no sku to send. */
    @Override
    public PagedResult<ChannelOrder> fetchOrders(ChannelConnectionRef connection, Instant since, Page page) {
        JsonNode json = get(connection, "/v2/orders?since=" + urlEncode(since.toString())
                + "&page=" + page.pageNumber() + "&pageSize=" + page.pageSize());

        List<ChannelOrder> orders = new ArrayList<>();
        for (JsonNode o : json.get("items")) {
            List<ChannelOrderItem> items = new ArrayList<>();
            for (JsonNode line : o.get("lines")) {
                // No seller sku on this channel at all — the barcode is the only identifier
                // it has, so it is both the correlation key and the item's identity (Plan §1:
                // sku stays null rather than being made to lie about having one).
                String barcode = line.get("barcode").asText();
                items.add(new ChannelOrderItem(new ChannelItemRef(barcode, null, barcode), line.get("quantity").asInt(),
                        new BigDecimal(line.get("unitPrice").asText())));
            }
            orders.add(new ChannelOrder(o.get("id").asText(), o.get("id").asText(),
                    Instant.parse(o.get("createdAt").asText()), null, items));
        }
        return toPagedResult(json, orders);
    }

    /**
     * This channel is barcode-keyed (Plan §1) — {@code channelVariantId} is what goes
     * on the wire, never sku, and it routes through /v2/stock/bulk-update. The v1 route
     * (/stock/bulk-update) belongs to the SKU-keyed shape and must never see a call from
     * this connector.
     */
    @Override
    public List<ItemResult> updateStock(ChannelConnectionRef connection, List<StockUpdate> batch) {
        var body = java.util.Map.of("updates", batch.stream()
                .map(u -> java.util.Map.of("channelVariantId", u.item().channelVariantId(), "quantity", u.availableQuantity()))
                .toList());
        return toItemResults(post(connection, "/v2/stock/bulk-update", body));
    }

    @Override
    public List<ItemResult> updatePrice(ChannelConnectionRef connection, List<PriceUpdate> batch) {
        var body = java.util.Map.of("updates", batch.stream()
                .map(u -> java.util.Map.of("channelVariantId", u.item().channelVariantId(), "price", u.price().toString()))
                .toList());
        return toItemResults(post(connection, "/v2/price/bulk-update", body));
    }

    @Override
    public ShipmentResult createShipment(ChannelConnectionRef connection, ShipmentRequest request, CallIntentRef intent) {
        var body = java.util.Map.of("intentId", intent.intentId().toString(), "orderId", request.channelOrderId());
        JsonNode json = post(connection, "/v2/shipments", body);
        return new ShipmentResult(json.get("id").asText(), json.get("trackingNumber").asText());
    }

    @Override
    public CallStatus queryCallStatus(ChannelConnectionRef connection, CallIntentRef intent) {
        JsonNode json = get(connection, "/call-status?intentId=" + urlEncode(intent.intentId().toString()));
        if (!json.get("found").asBoolean()) {
            return CallStatus.unresolved();
        }
        return CallStatus.resolved(json.get("result").toString());
    }

    // -------------------------------------------------------------------------
    // Capabilities this channel does not have.
    //
    // Each throws rather than returning something empty and plausible. A channel that
    // silently answers "no returns" is indistinguishable from one that has none, and
    // the resulting bug is a business process that quietly never runs.
    // -------------------------------------------------------------------------

    @Override
    public PagedResult<ChannelReturn> fetchReturns(ChannelConnectionRef connection, Instant since, Page page) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " does not expose returns");
    }

    @Override
    public ItemResult submitReturnDecision(ChannelConnectionRef connection, ReturnDecision decision, CallIntentRef intent) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " does not accept return decisions");
    }

    @Override
    public RefundResult issueRefund(ChannelConnectionRef connection, RefundRequest request, CallIntentRef intent) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " refunds customers itself");
    }

    /** No WEBHOOK capability, so nothing should ever present a signed request from this channel. */
    @Override
    public SignatureVerification verifySignature(ChannelConnectionRef connection, RawRequest request) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " does not send webhooks");
    }

    @Override
    public CredentialStatus checkCredentials(ChannelConnectionRef connection) {
        try {
            JsonNode json = get(connection, "/auth/status");
            return json.get("valid").asBoolean()
                    ? CredentialStatus.ok()
                    : CredentialStatus.invalid("channel reports invalid credentials");
        } catch (RuntimeException e) {
            return CredentialStatus.invalid(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private <T> PagedResult<T> toPagedResult(JsonNode json, List<T> items) {
        return new PagedResult<>(items, json.get("page").asInt(), json.get("pageSize").asInt(),
                json.get("totalPages").asInt(), json.get("hasMore").asBoolean());
    }

    private List<ItemResult> toItemResults(JsonNode json) {
        List<ItemResult> results = new ArrayList<>();
        for (JsonNode r : json.get("results")) {
            String channelVariantId = r.get("channelVariantId").asText();
            results.add(r.get("success").asBoolean()
                    ? ItemResult.success(channelVariantId)
                    : ItemResult.failure(channelVariantId, r.get("error").asText()));
        }
        return results;
    }

    private JsonNode get(ChannelConnectionRef connection, String path) {
        return send(HttpRequest.newBuilder(URI.create(connection.credentials() + path)).GET().build());
    }

    private JsonNode post(ChannelConnectionRef connection, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(connection.credentials() + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ChannelRateLimitedException(CHANNEL_TYPE + " returned 429 for " + request.uri());
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(CHANNEL_TYPE + " returned " + response.statusCode()
                        + " for " + request.uri() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted: " + request.uri(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
