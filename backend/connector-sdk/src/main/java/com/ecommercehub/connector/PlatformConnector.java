package com.ecommercehub.connector;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Plan §8: the one contract every channel implementation (MockConnector now,
 * Shopify/Trendyol later) has to satisfy, and the one the shared contract test suite
 * (ConnectorContractTest) runs against all of them.
 *
 * <p>Side-effecting calls (submitReturnDecision, createShipment) take a
 * {@link CallIntentRef} — the engine is at-least-once (Plan §1), so a naive retry of
 * "create a shipment" would create a second one. The intent's id becomes the
 * idempotency key; queryCallStatus is the crash-recovery path when a call's outcome
 * is unknown rather than a re-call.
 */
public interface PlatformConnector {

    String channelType();

    Set<Capability> capabilities();

    /**
     * {@code since} must always be sent with the Plan §8 overlap window
     * (last-successful-fetch minus ~5 minutes) applied by the caller — duplicates
     * that produces are absorbed by ingest-layer idempotency (Plan §4.1), not here.
     */
    PagedResult<ChannelOrder> fetchOrders(ChannelConnectionRef connection, Instant since, Page page);

    PagedResult<ChannelProduct> fetchCatalog(ChannelConnectionRef connection, Page page);

    /** Bulk call — 1000 SKUs is one request, not 1000 (Plan §8: "Toplu imza zorunlu"). */
    List<ItemResult> updateStock(ChannelConnectionRef connection, List<StockUpdate> batch);

    List<ItemResult> updatePrice(ChannelConnectionRef connection, List<PriceUpdate> batch);

    PagedResult<ChannelReturn> fetchReturns(ChannelConnectionRef connection, Instant since, Page page);

    ItemResult submitReturnDecision(ChannelConnectionRef connection, ReturnDecision decision, CallIntentRef intent);

    ShipmentResult createShipment(ChannelConnectionRef connection, ShipmentRequest request, CallIntentRef intent);

    /**
     * Pays a refund. Only callable when {@link Capability#REFUND_BY_US} is present — on
     * every other channel the marketplace is the merchant of record, refunds the customer
     * itself, and Plan §7 makes REFUNDED an event we observe rather than an act of ours.
     *
     * <p>This is the most dangerous call in the interface: it moves real money and is not
     * naturally idempotent. Like the others it carries an intent, and a call whose outcome
     * is unknown is resolved with {@link #queryCallStatus}, never by retrying.
     */
    RefundResult issueRefund(ChannelConnectionRef connection, RefundRequest request, CallIntentRef intent);

    /** Resolves an AMBIGUOUS intent — see Plan §3/§4.3 and ChannelCallIntentService.recoverStuckIntents. */
    CallStatus queryCallStatus(ChannelConnectionRef connection, CallIntentRef intent);

    /** Must verify against the raw, unparsed bytes — see RawRequest's javadoc. */
    SignatureVerification verifySignature(ChannelConnectionRef connection, RawRequest request);

    CredentialStatus checkCredentials(ChannelConnectionRef connection);
}
