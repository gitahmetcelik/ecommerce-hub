package com.ecommercehub.connector;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * plan §8: the one contract every channel implementation (MockConnector now,
 * Shopify/Trendyol later) has to satisfy, and the one the shared contract test suite
 * (ConnectorContractTest) runs against all of them.
 *
 * <p>Side-effecting calls (submitReturnDecision, createShipment) take a
 * {@link CallIntentRef} — the engine is at-least-once (plan §1), so a naive retry of
 * "create a shipment" would create a second one. The intent's id becomes the
 * idempotency key; queryCallStatus is the crash-recovery path when a call's outcome
 * is unknown rather than a re-call.
 */
public interface PlatformConnector {

    String channelType();

    Set<Capability> capabilities();

    /**
     * {@code since} must always be sent with the plan §8 overlap window
     * (last-successful-fetch minus ~5 minutes) applied by the caller — duplicates
     * that produces are absorbed by ingest-layer idempotency (plan §4.1), not here.
     */
    PagedResult<ChannelOrder> fetchOrders(ChannelConnectionRef connection, Instant since, Page page);

    PagedResult<ChannelProduct> fetchCatalog(ChannelConnectionRef connection, Page page);

    /** Bulk call — 1000 SKUs is one request, not 1000 (plan §8: "Toplu imza zorunlu"). */
    List<ItemResult> updateStock(ChannelConnectionRef connection, List<StockUpdate> batch);

    List<ItemResult> updatePrice(ChannelConnectionRef connection, List<PriceUpdate> batch);

    PagedResult<ChannelReturn> fetchReturns(ChannelConnectionRef connection, Instant since, Page page);

    ItemResult submitReturnDecision(ChannelConnectionRef connection, ReturnDecision decision, CallIntentRef intent);

    ShipmentResult createShipment(ChannelConnectionRef connection, ShipmentRequest request, CallIntentRef intent);

    /** Resolves a BELIRSİZ intent — see plan §3/§4.3 and ChannelCallIntentService.recoverStuckIntents. */
    CallStatus queryCallStatus(ChannelConnectionRef connection, CallIntentRef intent);

    /** Must verify against the raw, unparsed bytes — see RawRequest's javadoc. */
    SignatureVerification verifySignature(ChannelConnectionRef connection, RawRequest request);

    CredentialStatus checkCredentials(ChannelConnectionRef connection);
}
