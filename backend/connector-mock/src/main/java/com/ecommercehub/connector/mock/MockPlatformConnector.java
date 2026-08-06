package com.ecommercehub.connector.mock;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.CallStatus;
import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.ChannelConnectionRef;
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
import com.ecommercehub.connector.ReturnDecision;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.connector.SignatureVerification;
import com.ecommercehub.connector.StockUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * plan §8: talks to mock-pazaryeri over real HTTP (not an in-process fake) — the
 * first, throwaway-free implementer of {@link PlatformConnector} and what the
 * shared contract test suite runs against before any real channel exists.
 *
 * <p>{@link ChannelConnectionRef#credentials()} holds the mock server's base URL
 * (e.g. "http://localhost:4100") rather than an API key — MockConnector has nothing
 * to authenticate with, a real connector's credentials() would hold an encrypted
 * token instead.
 */
public class MockPlatformConnector implements PlatformConnector {

    // Matches mock-pazaryeri's /_admin/sign secret. A real connector would derive
    // its signing secret from the connection's own decrypted credentials instead.
    private static final String SHARED_SIGNING_SECRET = "mock-shared-secret";
    private static final String SIGNATURE_HEADER = "X-Mock-Signature";

    private static final Set<Capability> CAPABILITIES = EnumSet.of(
            Capability.FETCH_ORDERS, Capability.FETCH_CATALOG, Capability.STOCK_PUSH, Capability.PRICE_PUSH,
            Capability.RETURN_DECISION_SUBMIT, Capability.SHIPMENT_CREATE,
            Capability.WEBHOOK, Capability.REQUEST_IDEMPOTENCY_KEY);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MockPlatformConnector(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public MockPlatformConnector() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    @Override
    public String channelType() {
        return "MOCK";
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PagedResult<ChannelOrder> fetchOrders(ChannelConnectionRef connection, Instant since, Page page) {
        JsonNode json = get(connection, "/orders?since=" + urlEncode(since.toString())
                + "&page=" + page.pageNumber() + "&pageSize=" + page.pageSize());

        List<ChannelOrder> orders = new ArrayList<>();
        for (JsonNode o : json.get("items")) {
            List<ChannelOrderItem> items = new ArrayList<>();
            for (JsonNode i : o.get("items")) {
                items.add(new ChannelOrderItem(i.get("sku").asText(), i.get("quantity").asInt(),
                        new java.math.BigDecimal(i.get("unitPrice").asText())));
            }
            orders.add(new ChannelOrder(o.get("id").asText(), o.get("customerOrderNumber").asText(),
                    Instant.parse(o.get("createdAt").asText()), null, items));
        }
        return toPagedResult(json, orders);
    }

    @Override
    public PagedResult<ChannelProduct> fetchCatalog(ChannelConnectionRef connection, Page page) {
        JsonNode json = get(connection, "/catalog?page=" + page.pageNumber() + "&pageSize=" + page.pageSize());

        List<ChannelProduct> products = new ArrayList<>();
        for (JsonNode p : json.get("items")) {
            // A missing or null "stock" stays null rather than becoming 0 — the nightly
            // reconcile reads null as "the channel has no opinion" and skips it, while 0
            // would be taken as a real quantity and reported as drift on every variant.
            JsonNode stock = p.get("stock");
            Integer availableQuantity = stock == null || stock.isNull() ? null : stock.asInt();

            products.add(new ChannelProduct(p.get("id").asText(), p.get("id").asText(),
                    p.get("sku").asText(), p.get("barcode").asText(), p.get("title").asText(), availableQuantity));
        }
        return toPagedResult(json, products);
    }

    @Override
    public List<ItemResult> updateStock(ChannelConnectionRef connection, List<StockUpdate> batch) {
        var body = new java.util.HashMap<String, Object>();
        body.put("updates", batch.stream()
                .map(u -> java.util.Map.of("sku", u.sku(), "quantity", u.availableQuantity()))
                .toList());
        JsonNode json = post(connection, "/stock/bulk-update", body);
        return toItemResults(json);
    }

    @Override
    public List<ItemResult> updatePrice(ChannelConnectionRef connection, List<PriceUpdate> batch) {
        var body = new java.util.HashMap<String, Object>();
        body.put("updates", batch.stream()
                .map(u -> java.util.Map.of("sku", u.sku(), "price", u.price().toString()))
                .toList());
        JsonNode json = post(connection, "/price/bulk-update", body);
        return toItemResults(json);
    }

    @Override
    public PagedResult<ChannelReturn> fetchReturns(ChannelConnectionRef connection, Instant since, Page page) {
        JsonNode json = get(connection, "/returns?since=" + urlEncode(since.toString())
                + "&page=" + page.pageNumber() + "&pageSize=" + page.pageSize());

        List<ChannelReturn> returns = new ArrayList<>();
        for (JsonNode r : json.get("items")) {
            returns.add(new ChannelReturn(r.get("id").asText(), r.get("orderId").asText(),
                    Instant.parse(r.get("createdAt").asText()), r.get("status").asText()));
        }
        return toPagedResult(json, returns);
    }

    @Override
    public ItemResult submitReturnDecision(ChannelConnectionRef connection, ReturnDecision decision, CallIntentRef intent) {
        var body = java.util.Map.of("intentId", intent.intentId().toString(), "decision", decision.decision().name());
        JsonNode json = post(connection, "/returns/" + decision.channelReturnId() + "/decision", body);
        return ItemResult.success(json.get("id").asText());
    }

    @Override
    public ShipmentResult createShipment(ChannelConnectionRef connection, ShipmentRequest request, CallIntentRef intent) {
        var body = java.util.Map.of("intentId", intent.intentId().toString(), "orderId", request.channelOrderId());
        JsonNode json = post(connection, "/shipments", body);
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

    @Override
    public SignatureVerification verifySignature(ChannelConnectionRef connection, RawRequest request) {
        String provided = request.headers().get(SIGNATURE_HEADER);
        if (provided == null) {
            return SignatureVerification.invalid("missing " + SIGNATURE_HEADER + " header");
        }
        String expected = hmacSha256Hex(request.bodyBytes());
        if (!constantTimeEquals(provided, expected)) {
            return SignatureVerification.invalid("signature mismatch");
        }
        return SignatureVerification.ok();
    }

    @Override
    public CredentialStatus checkCredentials(ChannelConnectionRef connection) {
        try {
            JsonNode json = get(connection, "/auth/status");
            return json.get("valid").asBoolean() ? CredentialStatus.ok() : CredentialStatus.invalid("channel reports invalid credentials");
        } catch (RuntimeException e) {
            return CredentialStatus.invalid(e.getMessage());
        }
    }

    private <T> PagedResult<T> toPagedResult(JsonNode json, List<T> items) {
        return new PagedResult<>(items, json.get("page").asInt(), json.get("pageSize").asInt(),
                json.get("totalPages").asInt(), json.get("hasMore").asBoolean());
    }

    private List<ItemResult> toItemResults(JsonNode json) {
        List<ItemResult> results = new ArrayList<>();
        for (JsonNode r : json.get("results")) {
            boolean success = r.get("success").asBoolean();
            results.add(success
                    ? ItemResult.success(r.get("sku").asText())
                    : ItemResult.failure(r.get("sku").asText(), r.get("error").asText()));
        }
        return results;
    }

    private JsonNode get(ChannelConnectionRef connection, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(connection) + path)).GET().build();
        return send(request);
    }

    private JsonNode post(ChannelConnectionRef connection, String path, Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(connection) + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return send(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ChannelRateLimitedException("mock-pazaryeri returned 429 for " + request.uri());
            }
            if (response.statusCode() >= 400) {
                throw new RuntimeException("mock-pazaryeri returned " + response.statusCode() + " for " + request.uri()
                        + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new RuntimeException("HTTP call to mock-pazaryeri failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call to mock-pazaryeri interrupted: " + request.uri(), e);
        }
    }

    private String baseUrl(ChannelConnectionRef connection) {
        return connection.credentials();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hmacSha256Hex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SHARED_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
