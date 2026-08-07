package com.ecommercehub.domain.returns;

import com.ecommercehub.domain.intent.ChannelCallIntent;
import com.ecommercehub.domain.intent.IntentOutcomeApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Plan v5 §2.2, H3: a REFUND intent recovered by {@code recoverStuckIntents} must move
 * the return_payment row too, not just the intent. Before this existed, the only place
 * this logic ran was {@code ReturnFulfilmentService.resolveInFlightRefunds}, which
 * nothing in production ever called — so a recovered refund left the payment reading
 * PENDING forever, and the next operator to look at it authorised a second one.
 */
@Component
public class RefundIntentOutcomeApplier implements IntentOutcomeApplier {

    public static final String TYPE = "REFUND";

    private static final Logger log = LoggerFactory.getLogger(RefundIntentOutcomeApplier.class);

    private final ReturnPaymentRepository returnPaymentRepository;
    private final ReturnService returnService;
    private final ObjectMapper objectMapper;

    public RefundIntentOutcomeApplier(ReturnPaymentRepository returnPaymentRepository, ReturnService returnService,
                                       ObjectMapper objectMapper) {
        this.returnPaymentRepository = returnPaymentRepository;
        this.returnService = returnService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String intentType() {
        return TYPE;
    }

    @Override
    public void apply(ChannelCallIntent intent, String channelResponseJson) {
        returnPaymentRepository.findById(intent.getTargetReference()).ifPresent(payment -> {
            if (!ReturnPayment.STATUS_PENDING.equals(payment.getStatus())) {
                // Already applied — a resolver can be asked about the same intent more
                // than once before recoverStuckIntents next runs; re-applying must be a
                // no-op, not a second markPaid call.
                return;
            }
            payment.markPaid(extractId(channelResponseJson), Instant.now());
            returnService.markRefunded(intent.getOrganizationId(), payment.getReturnRequestId());
            log.info("Recovered in-flight refund for payment {} — the channel confirms it was already paid",
                    payment.getId());
        });
    }

    private String extractId(String channelResponseJson) {
        try {
            JsonNode node = objectMapper.readTree(channelResponseJson).get("id");
            return node == null || node.isNull() ? null : node.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
