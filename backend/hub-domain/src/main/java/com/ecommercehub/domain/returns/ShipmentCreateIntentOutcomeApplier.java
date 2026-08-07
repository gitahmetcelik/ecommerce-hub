package com.ecommercehub.domain.returns;

import com.ecommercehub.domain.intent.ChannelCallIntent;
import com.ecommercehub.domain.intent.IntentOutcomeApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Plan v5 §2.2, H3's second example: before this applier existed, {@code
 * applyRecoveredOutcome} silently skipped every intent type except REFUND — so a
 * recovered shipment label was resolved at the intent level and never written to the
 * shipment row at all, leaving the return stuck "awaiting a label" that the channel
 * had, in fact, already created.
 */
@Component
public class ShipmentCreateIntentOutcomeApplier implements IntentOutcomeApplier {

    public static final String TYPE = "SHIPMENT_CREATE";

    private static final Logger log = LoggerFactory.getLogger(ShipmentCreateIntentOutcomeApplier.class);

    private final ShipmentRepository shipmentRepository;
    private final ReturnService returnService;
    private final ObjectMapper objectMapper;

    public ShipmentCreateIntentOutcomeApplier(ShipmentRepository shipmentRepository, ReturnService returnService,
                                               ObjectMapper objectMapper) {
        this.shipmentRepository = shipmentRepository;
        this.returnService = returnService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String intentType() {
        return TYPE;
    }

    @Override
    public void apply(ChannelCallIntent intent, String channelResponseJson) {
        shipmentRepository.findById(intent.getTargetReference()).ifPresent(shipment -> {
            if (shipment.getTrackingNumber() != null) {
                // Already applied — see RefundIntentOutcomeApplier's identical guard.
                return;
            }
            JsonNode response = readTree(channelResponseJson);
            shipment.recordChannelResult(textOrNull(response, "id"), textOrNull(response, "trackingNumber"));
            returnService.markReturnShipmentCreated(intent.getOrganizationId(), shipment.getReturnRequestId());
            log.info("Recovered in-flight shipment for return {} — the channel confirms the label was already created",
                    shipment.getReturnRequestId());
        });
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
