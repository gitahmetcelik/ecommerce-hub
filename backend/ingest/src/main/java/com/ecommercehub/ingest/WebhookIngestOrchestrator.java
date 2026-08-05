package com.ecommercehub.ingest;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.RawRequest;
import com.ecommercehub.connector.SignatureVerification;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * plan §3(c): everything from setting the RLS tenant context onward must run inside
 * one transaction — this class is that transaction. The controller stays a thin
 * byte-reading adapter (see WebhookController) precisely so this invariant lives in
 * exactly one place instead of being re-derived per endpoint.
 */
@Service
public class WebhookIngestOrchestrator {

    private static final String SIGNATURE_HEADER = "X-Mock-Signature";

    private final TenantContextService tenantContextService;
    private final ChannelConnectionRepository channelConnectionRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final IngestService ingestService;
    private final ObjectMapper objectMapper;

    public WebhookIngestOrchestrator(TenantContextService tenantContextService,
                                      ChannelConnectionRepository channelConnectionRepository,
                                      CredentialEncryptionService credentialEncryptionService,
                                      ConnectorRegistry connectorRegistry,
                                      IngestService ingestService,
                                      ObjectMapper objectMapper) {
        this.tenantContextService = tenantContextService;
        this.channelConnectionRepository = channelConnectionRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookIngestResult receive(UUID organizationId, UUID channelConnectionId,
                                        byte[] bodyBytes, Map<String, String> headers) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ChannelConnection connection = channelConnectionRepository.findById(channelConnectionId)
                .filter(c -> c.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new UnknownChannelConnectionException(
                        "No channel_connection " + channelConnectionId + " for organization " + organizationId));

        String decryptedCredentials = credentialEncryptionService.decrypt(
                connection.getEncryptedCredentials(), connection.getKeyVersion());
        PlatformConnector connector = connectorRegistry.require(connection.getChannelType());
        ChannelConnectionRef connectionRef = new ChannelConnectionRef(
                connection.getId(), organizationId, connection.getChannelType(), decryptedCredentials);

        SignatureVerification verification = connector.verifySignature(connectionRef, new RawRequest(bodyBytes, headers));
        if (!verification.valid()) {
            throw new InvalidWebhookSignatureException("Signature verification failed: " + verification.reason());
        }

        String traceId = UUID.randomUUID().toString();
        WebhookEnvelope envelope = parseEnvelope(bodyBytes);
        OrderEventPayload payload = toOrderEventPayload(organizationId, channelConnectionId, envelope);

        boolean isNew = ingestService.ingest(organizationId, channelConnectionId, envelope.eventId(),
                new String(bodyBytes, StandardCharsets.UTF_8), headers.get(SIGNATURE_HEADER), traceId, payload);

        return new WebhookIngestResult(isNew, traceId);
    }

    private WebhookEnvelope parseEnvelope(byte[] bodyBytes) {
        try {
            return objectMapper.readValue(bodyBytes, WebhookEnvelope.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed webhook body", e);
        }
    }

    private OrderEventPayload toOrderEventPayload(UUID organizationId, UUID channelConnectionId, WebhookEnvelope envelope) {
        OrderItemStatus defaultTarget = EventTypeStatusMapping.defaultTargetFor(envelope.eventType());

        List<OrderEventPayload.OrderEventItem> items = envelope.order().items().stream()
                .map(item -> new OrderEventPayload.OrderEventItem(
                        item.sku(),
                        item.channelProductId() != null ? item.channelProductId() : item.sku(),
                        item.channelVariantId() != null ? item.channelVariantId() : item.sku(),
                        item.barcode(),
                        item.quantity(), item.unitPrice(),
                        item.vatRate() == null ? BigDecimal.ZERO : item.vatRate(),
                        item.targetStatus() == null ? defaultTarget : OrderItemStatus.valueOf(item.targetStatus())))
                .toList();

        return new OrderEventPayload(organizationId, channelConnectionId, envelope.eventId(),
                envelope.order().channelOrderNumber(), envelope.eventAt(), envelope.sequence(),
                envelope.order().total(), envelope.order().currency(), items, null);
    }
}
