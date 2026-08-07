package com.ecommercehub.connector.shopify;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan v5 Faz 4: the first real (non-mock) {@link PlatformConnector} — Shopify's Admin
 * GraphQL API, chosen over the originally-researched Trendyol because Trendyol's
 * credentials require an actual Seller ID (Plan v5 §Faz3 revision, 2026-08-07) and
 * Shopify's free Partner development store needed none. Everything this class does was
 * either run against a real Shopify dev store during the Faz 4 spike, or is called out
 * below as inferred-from-docs where the spike did not cover it — see
 * {@code docs/kanal-arastirmasi.md}'s "§4 spike sonuçları" for the full account.
 *
 * <h2>Two things the spike taught that the SDK did not assume</h2>
 *
 * <p><b>Bulk inventory writes are atomic per call, not per item.</b>
 * {@code inventorySetQuantities} rejects the <em>entire</em> batch the moment one
 * {@code inventoryItemId} is bad — confirmed live: a 3-item batch with 2 bad ids came
 * back with {@code inventoryAdjustmentGroup: null} and zero items applied, not 1
 * applied + 2 reported failed. Shopify does report every bad index in one response
 * though, so {@link #updateStock} recovers the per-item contract
 * ({@code ConnectorContractTest}'s partial-failure gate) by retrying once, without the
 * bad ids, rather than trusting the first response's shape directly.
 *
 * <p><b>Pagination is cursor-based, not page-numbered.</b> The SDK's {@link Page} is a
 * page number; Shopify's GraphQL connections only support "give me the next page after
 * this cursor". This connector bridges the two with a same-instance, sequential-only
 * cursor cache (see {@link #cursorCache}) — it works for the only access pattern the
 * codebase actually uses (walk page 1, 2, 3, ... in order, Plan v4 BackfillService /
 * ReconcileService), and throws rather than silently misbehaving if that assumption is
 * ever violated. A real cursor-native paging abstraction is future work, not Faz 4's.
 */
public class ShopifyPlatformConnector implements PlatformConnector {

    public static final String CHANNEL_TYPE = "SHOPIFY";
    static final String API_VERSION = "2025-10";

    /**
     * RETURN_DECISION_SUBMIT and REQUEST_IDEMPOTENCY_KEY are deliberately absent — the
     * Faz 4 spike did not verify either (see class javadoc and {@link #queryCallStatus}).
     * Declaring a capability the spike never proved is exactly the "tahmin, gözlem
     * değil" mistake Plan §4.2 exists to prevent.
     */
    private static final Set<Capability> CAPABILITIES = EnumSet.of(
            Capability.FETCH_ORDERS, Capability.FETCH_CATALOG,
            Capability.STOCK_PUSH, Capability.PRICE_PUSH,
            Capability.WEBHOOK, Capability.SHIPMENT_CREATE, Capability.REFUND_BY_US);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Keyed by {@code connectionId:bucket:since:pageNumber -> endCursor to use for
     * pageNumber+1}. Same-instance only, and only correct for a strictly sequential
     * walk — see class javadoc.
     */
    private final Map<String, String> cursorCache = new ConcurrentHashMap<>();

    public ShopifyPlatformConnector(HttpClient httpClient, ObjectMapper objectMapper) {
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

    @Override
    public PagedResult<ChannelOrder> fetchOrders(ChannelConnectionRef connection, Instant since, Page page) {
        ShopifyCredentials creds = credentials(connection);
        String after = afterCursorFor(connection.id().toString(), "orders", since, page);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("first", page.pageSize());
        variables.put("after", after);
        variables.put("query", "created_at:>='" + since + "'");

        JsonNode data = call(creds, """
                query($first: Int!, $after: String, $query: String) {
                  orders(first: $first, after: $after, query: $query, sortKey: CREATED_AT) {
                    edges {
                      cursor
                      node {
                        id
                        name
                        createdAt
                        lineItems(first: 100) {
                          edges {
                            node {
                              quantity
                              discountedUnitPriceSet { shopMoney { amount } }
                              variant { id sku barcode }
                            }
                          }
                        }
                      }
                    }
                    pageInfo { hasNextPage }
                  }
                }
                """, variables).get("orders");

        List<ChannelOrder> orders = new ArrayList<>();
        String lastCursor = null;
        for (JsonNode edge : data.get("edges")) {
            lastCursor = edge.get("cursor").asText();
            JsonNode node = edge.get("node");
            List<ChannelOrderItem> items = new ArrayList<>();
            for (JsonNode lineEdge : node.get("lineItems").get("edges")) {
                JsonNode line = lineEdge.get("node");
                JsonNode variant = line.get("variant");
                // A line item can outlive its variant (deleted product) — Shopify
                // returns variant: null rather than omitting the line.
                if (variant == null || variant.isNull()) {
                    continue;
                }
                items.add(new ChannelOrderItem(
                        new ChannelItemRef(variant.get("id").asText(), textOrNull(variant, "sku"), textOrNull(variant, "barcode")),
                        line.get("quantity").asInt(),
                        new java.math.BigDecimal(line.get("discountedUnitPriceSet").get("shopMoney").get("amount").asText())));
            }
            orders.add(new ChannelOrder(node.get("id").asText(), node.get("name").asText(),
                    Instant.parse(node.get("createdAt").asText()), null, items));
        }

        boolean hasMore = data.get("pageInfo").get("hasNextPage").asBoolean();
        rememberCursor(connection.id().toString(), "orders", since, page, hasMore ? lastCursor : null);
        return new PagedResult<>(orders, page.pageNumber(), page.pageSize(),
                page.pageNumber() + (hasMore ? 1 : 0), hasMore);
    }

    @Override
    public PagedResult<ChannelProduct> fetchCatalog(ChannelConnectionRef connection, Page page) {
        ShopifyCredentials creds = credentials(connection);
        String after = afterCursorFor(connection.id().toString(), "catalog", null, page);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("first", page.pageSize());
        variables.put("after", after);

        JsonNode data = call(creds, """
                query($first: Int!, $after: String) {
                  productVariants(first: $first, after: $after) {
                    edges {
                      cursor
                      node {
                        id
                        sku
                        barcode
                        product { id title }
                        inventoryItem {
                          tracked
                          inventoryLevels(first: 1) {
                            edges { node { quantities(names: ["available"]) { quantity } } }
                          }
                        }
                      }
                    }
                    pageInfo { hasNextPage }
                  }
                }
                """, variables).get("productVariants");

        List<ChannelProduct> products = new ArrayList<>();
        String lastCursor = null;
        for (JsonNode edge : data.get("edges")) {
            lastCursor = edge.get("cursor").asText();
            JsonNode node = edge.get("node");
            JsonNode inventoryItem = node.get("inventoryItem");

            // Untracked stays null rather than 0 — same reasoning the mock connectors'
            // fetchCatalog already documents: 0 would be read as real drift by the
            // nightly reconcile, null is "the channel has no opinion".
            Integer availableQuantity = null;
            if (inventoryItem.get("tracked").asBoolean()) {
                JsonNode levelEdges = inventoryItem.get("inventoryLevels").get("edges");
                if (!levelEdges.isEmpty()) {
                    availableQuantity = levelEdges.get(0).get("node").get("quantities").get(0).get("quantity").asInt();
                }
            }

            products.add(new ChannelProduct(node.get("product").get("id").asText(), node.get("id").asText(),
                    textOrNull(node, "sku"), textOrNull(node, "barcode"), node.get("product").get("title").asText(),
                    availableQuantity));
        }

        boolean hasMore = data.get("pageInfo").get("hasNextPage").asBoolean();
        rememberCursor(connection.id().toString(), "catalog", null, page, hasMore ? lastCursor : null);
        return new PagedResult<>(products, page.pageNumber(), page.pageSize(),
                page.pageNumber() + (hasMore ? 1 : 0), hasMore);
    }

    @Override
    public List<ItemResult> updateStock(ChannelConnectionRef connection, List<StockUpdate> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        ShopifyCredentials creds = credentials(connection);
        Map<String, InventoryRef> refs = resolveInventoryRefs(creds,
                batch.stream().map(u -> u.item().channelVariantId()).distinct().toList());

        List<ItemResult> results = new ArrayList<>();
        Map<String, StockUpdate> resolvable = new LinkedHashMap<>();
        for (StockUpdate u : batch) {
            InventoryRef ref = refs.get(u.item().channelVariantId());
            if (ref == null) {
                results.add(ItemResult.failure(u.item().channelVariantId(),
                        "variant not found or has no inventory location"));
            } else {
                resolvable.put(u.item().channelVariantId(), u);
            }
        }
        results.addAll(setInventoryQuantities(creds, refs, resolvable));
        return results;
    }

    @Override
    public List<ItemResult> updatePrice(ChannelConnectionRef connection, List<PriceUpdate> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        ShopifyCredentials creds = credentials(connection);
        Map<String, String> productIdByVariant = resolveProductIds(creds,
                batch.stream().map(u -> u.item().channelVariantId()).distinct().toList());

        List<ItemResult> results = new ArrayList<>();
        Map<String, List<PriceUpdate>> byProduct = new LinkedHashMap<>();
        for (PriceUpdate u : batch) {
            String productId = productIdByVariant.get(u.item().channelVariantId());
            if (productId == null) {
                results.add(ItemResult.failure(u.item().channelVariantId(), "variant not found"));
                continue;
            }
            byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(u);
        }
        for (Map.Entry<String, List<PriceUpdate>> group : byProduct.entrySet()) {
            results.addAll(setVariantPrices(creds, group.getKey(), group.getValue()));
        }
        return results;
    }

    /**
     * Unlike {@link #createShipment}/{@link #issueRefund} (spike-verified live),
     * {@code returns}/{@code ReturnLineItem}'s exact schema was never called for real —
     * it was outside the Faz 4 spike's mandatory checklist (orders, catalog, bulk stock,
     * a refund). {@code ReconcileService.reconcileReturns} calls this unconditionally,
     * on every connection, every hourly sweep, with no capability check gating it — so
     * a wrong guessed query here would be silent-wrong in production on a schedule, not
     * a one-off mistake caught in review. Refusing loudly is the same choice
     * {@link #submitReturnDecision} makes, for the same reason: a channel must not be
     * made to look like it has a capability the spike never actually proved.
     */
    @Override
    public PagedResult<ChannelReturn> fetchReturns(ChannelConnectionRef connection, Instant since, Page page) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " return fetching was not verified by the "
                + "Faz 4 spike and is not declared as a capability");
    }

    /** RETURN_DECISION_SUBMIT is not declared (see class javadoc) — nothing should call this. */
    @Override
    public ItemResult submitReturnDecision(ChannelConnectionRef connection, ReturnDecision decision, CallIntentRef intent) {
        throw new UnsupportedOperationException(CHANNEL_TYPE + " return-decision submission was not verified by the "
                + "Faz 4 spike and is not declared as a capability");
    }

    /**
     * {@code intent} is unused on purpose — {@code fulfillmentCreate} has no verified
     * client-reference field to carry {@link CallIntentRef#channelIdempotencyKey()}
     * into (class javadoc), so nothing here can honestly claim repeats are safe. That
     * is exactly why REQUEST_IDEMPOTENCY_KEY is not in {@link #capabilities()}.
     */
    @Override
    public ShipmentResult createShipment(ChannelConnectionRef connection, ShipmentRequest request, CallIntentRef intent) {
        ShopifyCredentials creds = credentials(connection);

        JsonNode fulfillmentOrdersData = call(creds, """
                query($orderId: ID!) {
                  order(id: $orderId) {
                    fulfillmentOrders(first: 10, query: "status:open") {
                      edges { node { id } }
                    }
                  }
                }
                """, Map.of("orderId", request.channelOrderId())).get("order");

        List<Map<String, Object>> lineItemsByFulfillmentOrder = new ArrayList<>();
        for (JsonNode edge : fulfillmentOrdersData.get("fulfillmentOrders").get("edges")) {
            lineItemsByFulfillmentOrder.add(Map.of("fulfillmentOrderId", edge.get("node").get("id").asText()));
        }
        if (lineItemsByFulfillmentOrder.isEmpty()) {
            throw new IllegalStateException("Order " + request.channelOrderId() + " has no open fulfillment orders");
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("lineItemsByFulfillmentOrder", lineItemsByFulfillmentOrder);
        input.put("notifyCustomer", false);

        JsonNode result = call(creds, """
                mutation($fulfillment: FulfillmentInput!) {
                  fulfillmentCreate(fulfillment: $fulfillment) {
                    fulfillment { id trackingInfo { number } }
                    userErrors { field message }
                  }
                }
                """, Map.of("fulfillment", input)).get("fulfillmentCreate");

        requireNoUserErrors(result);
        JsonNode fulfillment = result.get("fulfillment");
        JsonNode trackingInfo = fulfillment.get("trackingInfo");
        String trackingNumber = trackingInfo != null && !trackingInfo.isEmpty()
                ? trackingInfo.get(0).path("number").asText(null) : null;
        return new ShipmentResult(fulfillment.get("id").asText(), trackingNumber);
    }

    /** {@code intent} is unused for the same reason as {@link #createShipment} — see its javadoc. */
    @Override
    public RefundResult issueRefund(ChannelConnectionRef connection, RefundRequest request, CallIntentRef intent) {
        ShopifyCredentials creds = credentials(connection);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("orderId", request.channelOrderId());
        input.put("notify", false);

        JsonNode result = call(creds, """
                mutation($input: RefundInput!) {
                  refundCreate(input: $input) {
                    refund { id }
                    userErrors { field message }
                  }
                }
                """, Map.of("input", input)).get("refundCreate");

        requireNoUserErrors(result);
        return new RefundResult(result.get("refund").get("id").asText());
    }

    /**
     * Spike finding, not a full answer: neither {@code fulfillmentCreate} nor
     * {@code refundCreate} was found to accept (or echo back) an arbitrary client
     * reference token, so there is no verified way to ask Shopify "did the call with
     * this intent already happen". Returning unresolved is the safe default — the
     * intent escalates to AMBIGUOUS and the operator queue (Plan §3) rather than this
     * connector guessing. Resolving this for real (e.g. a searchable {@code note}/
     * {@code tags} field carrying the intent id) is follow-up work, not Faz 4's.
     */
    @Override
    public CallStatus queryCallStatus(ChannelConnectionRef connection, CallIntentRef intent) {
        return CallStatus.unresolved();
    }

    @Override
    public SignatureVerification verifySignature(ChannelConnectionRef connection, RawRequest request) {
        // Documented scheme (shopify.dev), not spike-verified — the spike exercised the
        // Admin API only, no webhook was ever registered against the dev store.
        String provided = request.headers().get("X-Shopify-Hmac-Sha256");
        if (provided == null) {
            return SignatureVerification.invalid("missing X-Shopify-Hmac-Sha256 header");
        }
        ShopifyCredentials creds = credentials(connection);
        if (creds.webhookSecret() == null) {
            // Not the Admin API access token — Shopify signs webhooks with the app's
            // client secret, a different credential entirely. Verifying against the
            // wrong key would be worse than refusing: it would either reject every
            // genuine webhook or (if the two ever happened to collide) accept a forged
            // one, and there is no way to tell which without the real secret.
            return SignatureVerification.invalid("no webhook secret configured for this connection");
        }
        String expected = hmacSha256Base64(creds.webhookSecret(), request.bodyBytes());
        if (!constantTimeEquals(provided, expected)) {
            return SignatureVerification.invalid("signature mismatch");
        }
        return SignatureVerification.ok();
    }

    @Override
    public CredentialStatus checkCredentials(ChannelConnectionRef connection) {
        try {
            ShopifyCredentials creds = credentials(connection);
            call(creds, "{ shop { name } }", Map.of());
            return CredentialStatus.ok();
        } catch (RuntimeException e) {
            return CredentialStatus.invalid(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Bulk write helpers
    // -------------------------------------------------------------------------

    /**
     * {@code inventorySetQuantities} is atomic per call (Plan v5 Faz 4 spike, class
     * javadoc) — a batch with any bad inventory item applies nothing at all. This
     * recovers the SDK's per-item contract by retrying once without whichever ids
     * Shopify's {@code userErrors} named, which the spike confirmed reports every bad
     * index in one response (not just the first).
     */
    private List<ItemResult> setInventoryQuantities(ShopifyCredentials creds, Map<String, InventoryRef> refs,
                                                      Map<String, StockUpdate> resolvable) {
        if (resolvable.isEmpty()) {
            return List.of();
        }

        List<String> order = new ArrayList<>(resolvable.keySet());
        JsonNode result = callInventorySetQuantities(creds, refs, resolvable, order);

        JsonNode userErrors = result.get("userErrors");
        if (userErrors.isEmpty()) {
            return order.stream().map(ItemResult::success).toList();
        }

        Map<Integer, String> errorByIndex = new LinkedHashMap<>();
        for (JsonNode error : userErrors) {
            JsonNode field = error.get("field");
            int index = Integer.parseInt(field.get(field.size() - 2).asText());
            errorByIndex.put(index, error.get("message").asText());
        }

        List<String> retryOrder = new ArrayList<>();
        List<ItemResult> results = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String channelVariantId = order.get(i);
            String error = errorByIndex.get(i);
            if (error != null) {
                results.add(ItemResult.failure(channelVariantId, error));
            } else {
                retryOrder.add(channelVariantId);
            }
        }

        if (!retryOrder.isEmpty()) {
            Map<String, StockUpdate> retryBatch = new LinkedHashMap<>();
            retryOrder.forEach(id -> retryBatch.put(id, resolvable.get(id)));
            JsonNode retryResult = callInventorySetQuantities(creds, refs, retryBatch, retryOrder);
            if (retryResult.get("userErrors").isEmpty()) {
                retryOrder.forEach(id -> results.add(ItemResult.success(id)));
            } else {
                // The retry itself failed for a reason unrelated to the ids we already
                // excluded (e.g. a transient error) — every remaining item is unresolved
                // rather than guessed at either way.
                String message = retryResult.get("userErrors").get(0).get("message").asText();
                retryOrder.forEach(id -> results.add(ItemResult.failure(id, message)));
            }
        }
        return results;
    }

    private JsonNode callInventorySetQuantities(ShopifyCredentials creds, Map<String, InventoryRef> refs,
                                                 Map<String, StockUpdate> batch, List<String> order) {
        List<Map<String, Object>> quantities = new ArrayList<>();
        for (String channelVariantId : order) {
            InventoryRef ref = refs.get(channelVariantId);
            quantities.add(Map.of(
                    "inventoryItemId", ref.inventoryItemId(),
                    "locationId", ref.locationId(),
                    "quantity", batch.get(channelVariantId).availableQuantity()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "available");
        input.put("reason", "correction");
        input.put("ignoreCompareQuantity", true);
        input.put("quantities", quantities);

        return call(creds, """
                mutation($input: InventorySetQuantitiesInput!) {
                  inventorySetQuantities(input: $input) {
                    inventoryAdjustmentGroup { changes { name delta } }
                    userErrors { field message }
                  }
                }
                """, Map.of("input", input)).get("inventorySetQuantities");
    }

    /** {@code productVariantsBulkUpdate} groups by product (Shopify schema constraint) — assumed atomic per call by analogy with inventorySetQuantities; not separately spike-verified. */
    private List<ItemResult> setVariantPrices(ShopifyCredentials creds, String productId, List<PriceUpdate> updates) {
        List<Map<String, Object>> variants = updates.stream()
                .map(u -> Map.<String, Object>of("id", u.item().channelVariantId(), "price", u.price().toPlainString()))
                .toList();

        JsonNode result = call(creds, """
                mutation($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
                  productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                    productVariants { id }
                    userErrors { field message }
                  }
                }
                """, Map.of("productId", productId, "variants", variants)).get("productVariantsBulkUpdate");

        JsonNode userErrors = result.get("userErrors");
        if (userErrors.isEmpty()) {
            return updates.stream().map(u -> ItemResult.success(u.item().channelVariantId())).toList();
        }
        String message = userErrors.get(0).get("message").asText();
        return updates.stream().map(u -> ItemResult.failure(u.item().channelVariantId(), message)).toList();
    }

    private record InventoryRef(String inventoryItemId, String locationId) {
    }

    /** One lookup call resolving each variant's inventory item + its first stocked location. */
    private Map<String, InventoryRef> resolveInventoryRefs(ShopifyCredentials creds, List<String> variantGids) {
        JsonNode nodes = call(creds, """
                query($ids: [ID!]!) {
                  nodes(ids: $ids) {
                    ... on ProductVariant {
                      id
                      inventoryItem {
                        id
                        inventoryLevels(first: 1) { edges { node { location { id } } } }
                      }
                    }
                  }
                }
                """, Map.of("ids", variantGids)).get("nodes");

        Map<String, InventoryRef> result = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            JsonNode levelEdges = node.get("inventoryItem").get("inventoryLevels").get("edges");
            if (levelEdges.isEmpty()) {
                continue;
            }
            result.put(node.get("id").asText(), new InventoryRef(
                    node.get("inventoryItem").get("id").asText(),
                    levelEdges.get(0).get("node").get("location").get("id").asText()));
        }
        return result;
    }

    private Map<String, String> resolveProductIds(ShopifyCredentials creds, List<String> variantGids) {
        JsonNode nodes = call(creds, """
                query($ids: [ID!]!) {
                  nodes(ids: $ids) {
                    ... on ProductVariant { id product { id } }
                  }
                }
                """, Map.of("ids", variantGids)).get("nodes");

        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            result.put(node.get("id").asText(), node.get("product").get("id").asText());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Paging cursor cache — see class javadoc
    // -------------------------------------------------------------------------

    private String afterCursorFor(String connectionId, String bucket, Instant since, Page page) {
        if (page.pageNumber() == 1) {
            return null;
        }
        String key = cursorKey(connectionId, bucket, since, page.pageNumber() - 1);
        String cursor = cursorCache.get(key);
        if (cursor == null) {
            throw new IllegalStateException("ShopifyPlatformConnector only supports fetching pages in strict "
                    + "sequence — page " + (page.pageNumber() - 1) + " must be fetched (on this connector "
                    + "instance) immediately before page " + page.pageNumber());
        }
        return cursor;
    }

    private void rememberCursor(String connectionId, String bucket, Instant since, Page page, String endCursor) {
        if (endCursor == null) {
            return;
        }
        cursorCache.put(cursorKey(connectionId, bucket, since, page.pageNumber()), endCursor);
    }

    private String cursorKey(String connectionId, String bucket, Instant since, int pageNumber) {
        return connectionId + ":" + bucket + ":" + since + ":" + pageNumber;
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    private ShopifyCredentials credentials(ChannelConnectionRef connection) {
        return ShopifyCredentials.parse(objectMapper, connection.credentials());
    }

    /**
     * A bare store domain (production: {@code my-store.myshopify.com}) is always
     * called over https. A value that already carries a scheme is used as-is — the
     * contract test's WireMock stand-in has no https listener, and there is nothing
     * Shopify-specific to relax otherwise.
     */
    private static String baseUrl(String storeDomain) {
        return storeDomain.startsWith("http://") || storeDomain.startsWith("https://")
                ? storeDomain : "https://" + storeDomain;
    }

    private JsonNode call(ShopifyCredentials creds, String query, Map<String, Object> variables) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("variables", variables);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            baseUrl(creds.storeDomain()) + "/admin/api/" + API_VERSION + "/graphql.json"))
                    .header("Content-Type", "application/json")
                    .header("X-Shopify-Access-Token", creds.accessToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ChannelRateLimitedException(CHANNEL_TYPE + " returned 429 for " + request.uri());
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(CHANNEL_TYPE + " returned " + response.statusCode()
                        + " for " + request.uri() + ": " + response.body());
            }

            JsonNode parsed = objectMapper.readTree(response.body());
            JsonNode errors = parsed.get("errors");
            if (errors != null && !errors.isEmpty()) {
                // GraphQL cost throttling comes back as HTTP 200 with a THROTTLED error
                // code, not an HTTP 429 (documented Shopify behavior, not spike-triggered
                // — reaching the 2000-point bucket in a throwaway spike wasn't worth the
                // call volume it would have taken).
                boolean throttled = throttleErrorCode(errors);
                if (throttled) {
                    throw new ChannelRateLimitedException(CHANNEL_TYPE + " GraphQL cost budget exhausted (THROTTLED)");
                }
                throw new IllegalStateException(CHANNEL_TYPE + " GraphQL errors: " + errors);
            }
            return parsed.get("data");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call to " + CHANNEL_TYPE + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call to " + CHANNEL_TYPE + " interrupted", e);
        }
    }

    private boolean throttleErrorCode(JsonNode errors) {
        for (JsonNode error : errors) {
            String code = error.path("extensions").path("code").asText("");
            if ("THROTTLED".equals(code)) {
                return true;
            }
        }
        return false;
    }

    private void requireNoUserErrors(JsonNode result) {
        JsonNode userErrors = result.get("userErrors");
        if (userErrors != null && !userErrors.isEmpty()) {
            throw new IllegalStateException(CHANNEL_TYPE + " rejected the call: " + userErrors);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String hmacSha256Base64(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
