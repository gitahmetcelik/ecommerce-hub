package com.ecommercehub.app.backfill;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.ChannelProduct;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Plan Phase 3: one page per call, cursor persisted after every page (Plan §3
 * "the page cursor is persistent"), always through the BACKGROUND budget class (Plan §9:
 * "backfill always runs in the BACKGROUND class"). If the budget has nothing left this tick,
 * the call is a no-op — the scheduler just tries again later, never blocks.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("worker")} — its only caller is {@code BackfillScheduler}.
 */
@Service
@Profile("worker")
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final ChannelConnectionRepository channelConnectionRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final CatalogMatchingService catalogMatchingService;
    private final OrderProcessingService orderProcessingService;
    private final ChannelBudgetRegistry budgetRegistry;
    private final TenantContextService tenantContextService;
    private final ObjectMapper objectMapper;
    private final BackfillProperties properties;

    public BackfillService(ChannelConnectionRepository channelConnectionRepository,
                            CredentialEncryptionService credentialEncryptionService,
                            ConnectorRegistry connectorRegistry,
                            CatalogMatchingService catalogMatchingService,
                            OrderProcessingService orderProcessingService,
                            ChannelBudgetRegistry budgetRegistry,
                            TenantContextService tenantContextService,
                            ObjectMapper objectMapper,
                            BackfillProperties properties) {
        this.channelConnectionRepository = channelConnectionRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.catalogMatchingService = catalogMatchingService;
        this.orderProcessingService = orderProcessingService;
        this.budgetRegistry = budgetRegistry;
        this.tenantContextService = tenantContextService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs exactly one page's worth of work (catalog first, then orders) and
     * persists the resulting cursor before returning. Safe to call repeatedly —
     * including "again after a simulated crash" — since every step it takes is
     * itself idempotent (catalog import matches-or-creates by sku, order
     * processing is target-status idempotent).
     */
    @Transactional
    public BackfillCursor runOneCycle(UUID organizationId, UUID channelConnectionId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ChannelConnection connection = channelConnectionRepository.findById(channelConnectionId)
                .orElseThrow(() -> new IllegalArgumentException("No channel_connection " + channelConnectionId));

        BackfillCursor cursor = readCursor(connection);
        if (cursor.isComplete()) {
            return cursor;
        }

        RateLimitBudget budget = budgetRegistry.forConnection(organizationId, channelConnectionId);
        if (!budget.tryAcquire(BudgetClass.BACKGROUND)) {
            log.info("Backfill for connection {} skipped this tick — BACKGROUND budget exhausted", channelConnectionId);
            return cursor;
        }

        PlatformConnector connector = connectorRegistry.require(connection.getChannelType());
        ChannelConnectionRef connectionRef = new ChannelConnectionRef(connection.getId(), organizationId,
                connection.getChannelType(), credentialEncryptionService.decrypt(connection.getEncryptedCredentials(), connection.getKeyVersion()));

        BackfillCursor next = cursor.catalogDone()
                ? runOrderPage(organizationId, channelConnectionId, connector, connectionRef, cursor)
                : runCatalogPage(organizationId, channelConnectionId, connector, connectionRef, cursor);

        writeCursor(connection, next);
        return next;
    }

    private BackfillCursor runCatalogPage(UUID organizationId, UUID channelConnectionId, PlatformConnector connector,
                                           ChannelConnectionRef connectionRef, BackfillCursor cursor) {
        PagedResult<ChannelProduct> page = connector.fetchCatalog(connectionRef, new Page(cursor.catalogPage(), properties.getPageSize()));

        for (ChannelProduct product : page.items()) {
            catalogMatchingService.importFromChannel(organizationId, channelConnectionId,
                    product.channelProductId(), product.channelVariantId(), product.sku(), product.barcode(), product.title());
        }

        log.info("Backfill catalog page {} for connection {}: {} item(s), hasMore={}",
                cursor.catalogPage(), channelConnectionId, page.items().size(), page.hasMore());

        return page.hasMore() ? cursor.withNextCatalogPage() : cursor.withCatalogDone();
    }

    private BackfillCursor runOrderPage(UUID organizationId, UUID channelConnectionId, PlatformConnector connector,
                                         ChannelConnectionRef connectionRef, BackfillCursor cursor) {
        PagedResult<ChannelOrder> page = connector.fetchOrders(connectionRef, cursor.orderSince(), new Page(cursor.orderPage(), properties.getPageSize()));

        for (ChannelOrder order : page.items()) {
            orderProcessingService.process(toOrderEventPayload(organizationId, channelConnectionId, order));
        }

        log.info("Backfill order page {} for connection {}: {} order(s), hasMore={}",
                cursor.orderPage(), channelConnectionId, page.items().size(), page.hasMore());

        if (page.hasMore()) {
            return cursor.withNextOrderPage();
        }
        log.info("Backfill complete for connection {}", channelConnectionId);
        return cursor.withOrdersDone(java.time.Instant.now());
    }

    /**
     * Historical orders are imported at CREATED — plan's ChannelOrder DTO carries no
     * status field yet (it will need one once a real connector exposes it); this is a
     * defensible baseline given what's actually available, not a claim that every
     * backfilled order is literally still in its earliest state.
     */
    private OrderEventPayload toOrderEventPayload(UUID organizationId, UUID channelConnectionId, ChannelOrder order) {
        // item.item().sku() is genuinely null on a barcode-keyed channel (Plan v5 §1) —
        // channelVariantId is the one identifier every shape guarantees, so it stands in
        // for channelProductId too when the channel gives us nothing more specific.
        List<OrderEventPayload.OrderEventItem> items = order.items().stream()
                .map(item -> new OrderEventPayload.OrderEventItem(item.item().sku(), item.item().channelVariantId(),
                        item.item().channelVariantId(), item.item().barcode(),
                        item.quantity(), item.unitPrice(), BigDecimal.ZERO, OrderItemStatus.CREATED))
                .toList();

        BigDecimal total = order.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrderEventPayload(organizationId, channelConnectionId, "backfill:" + order.channelOrderId(),
                order.channelOrderId(), order.eventAt(), order.channelEventSequence(), total, "USD", items, null);
    }

    private BackfillCursor readCursor(ChannelConnection connection) {
        String json = connection.getBackfillStatus();
        if (json == null || json.isBlank()) {
            return BackfillCursor.initial();
        }
        try {
            return objectMapper.readValue(json, BackfillCursor.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt backfill_status for connection " + connection.getId(), e);
        }
    }

    private void writeCursor(ChannelConnection connection, BackfillCursor cursor) {
        try {
            connection.setBackfillStatus(objectMapper.writeValueAsString(cursor));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
