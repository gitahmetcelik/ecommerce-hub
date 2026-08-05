package com.ecommercehub.domain.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * plan §4.3 / §3 kanal_cagri_niyeti: every side-effecting connector call (ship a
 * package, decide a return, refund money) goes through this three-step flow —
 * <ol>
 *   <li>{@link #prepare} commits a PREPARED row <em>before</em> anything is sent</li>
 *   <li>{@link #markSent} commits SENT <em>before</em> the actual network call is made</li>
 *   <li>{@link #recordResult} commits RESULT_RECEIVED after a response comes back</li>
 * </ol>
 * A crash between steps 2 and 3 leaves a row stuck at SENT. The engine is
 * at-least-once (plan §1), so retrying naively would re-issue the call — a second
 * shipping label, a second refund. {@link #recoverStuckIntents} is what a restart (or
 * the continuous reconcile pass, plan §11) must call instead: it asks the channel what
 * actually happened rather than repeating the side effect.
 */
@Service
public class ChannelCallIntentService {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallIntentService.class);

    private final ChannelCallIntentRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public ChannelCallIntentService(ChannelCallIntentRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Commits a new PREPARED intent. Must be called only after the domain row the
     * action belongs to (kargo, iade_odemesi, ...) is already committed — target_reference
     * points at that row's id, and the UNIQUE(organization_id, type, target_reference)
     * constraint is what makes a second shipment for the same kargo.id impossible while
     * still allowing a different kargo.id (partial re-shipment) to get its own intent.
     */
    @Transactional
    public ChannelCallIntent prepare(UUID organizationId, UUID channelConnectionId, String type,
                                      UUID targetReference, String requestSummaryJson) {
        ChannelCallIntent intent = new ChannelCallIntent(
                UUID.randomUUID(), organizationId, channelConnectionId, type, targetReference, requestSummaryJson);
        try {
            return repository.saveAndFlush(intent);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIntentException(
                    "An intent for type=" + type + " targetReference=" + targetReference + " already exists", e);
        }
    }

    /** Commits SENT — call this immediately before making the actual connector call. */
    @Transactional
    public void markSent(UUID intentId) {
        ChannelCallIntent intent = getOrThrow(intentId);
        intent.markSent();
    }

    /** Commits RESULT_RECEIVED after the connector call returns (or after durumSorgula resolves it). */
    @Transactional
    public void recordResult(UUID intentId, String channelResponseJson) {
        ChannelCallIntent intent = getOrThrow(intentId);
        intent.recordResult(channelResponseJson);
    }

    /**
     * Resolves every SENT intent older than {@code minAge} by asking the channel what
     * happened, instead of assuming failure and re-calling. An intent the resolver can't
     * resolve either is marked AMBIGUOUS and escalated to the operator queue (plan §3:
     * "sonuç alınamıyorsa BELIRSİZ → operatör kuyruğu") rather than left to retry forever.
     */
    @Transactional
    public int recoverStuckIntents(IntentStatusResolver resolver, java.time.Duration minAge) {
        Instant cutoff = Instant.now().minus(minAge.toMillis(), ChronoUnit.MILLIS);
        List<ChannelCallIntent> stuck = repository.findByStatusAndUpdatedAtBefore(IntentStatus.SENT, cutoff);

        int resolved = 0;
        for (ChannelCallIntent intent : stuck) {
            Optional<String> response = resolver.queryStatus(intent);
            if (response.isPresent()) {
                intent.recordResult(response.get());
                resolved++;
            } else {
                intent.markAmbiguous();
                escalateToOperatorQueue(intent);
                log.warn("Intent {} (type={}, targetReference={}) is AMBIGUOUS after durumSorgula — escalated",
                        intent.getId(), intent.getType(), intent.getTargetReference());
            }
        }
        return resolved;
    }

    private void escalateToOperatorQueue(ChannelCallIntent intent) {
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (gen_random_uuid(), ?, 'INTENT_AMBIGUOUS', ?, ?)
                """,
                intent.getOrganizationId(),
                "Channel call intent " + intent.getId() + " (" + intent.getType() + ") could not be resolved via durumSorgula",
                intent.getId());
    }

    @Transactional(readOnly = true)
    public Optional<ChannelCallIntent> findById(UUID intentId) {
        return repository.findById(intentId);
    }

    private ChannelCallIntent getOrThrow(UUID intentId) {
        return repository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("No channel_call_intent with id " + intentId));
    }
}
