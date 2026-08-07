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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Plan §4.3 / §3 kanal_cagri_niyeti: every side-effecting connector call (ship a
 * package, decide a return, refund money) goes through this three-step flow —
 * <ol>
 *   <li>{@link #prepare} commits a PREPARED row <em>before</em> anything is sent</li>
 *   <li>{@link #markSent} commits SENT <em>before</em> the actual network call is made</li>
 *   <li>{@link #recordResult} commits RESULT_RECEIVED after a response comes back</li>
 * </ol>
 * A crash between steps 2 and 3 leaves a row stuck at SENT. The engine is
 * at-least-once (Plan §1), so retrying naively would re-issue the call — a second
 * shipping label, a second refund. {@link #recoverStuckIntents} is what a restart (or
 * the continuous reconcile pass, Plan §11) must call instead: it asks the channel what
 * actually happened rather than repeating the side effect.
 */
@Service
public class ChannelCallIntentService {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallIntentService.class);

    private final ChannelCallIntentRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, IntentOutcomeApplier> appliersByType;

    public ChannelCallIntentService(ChannelCallIntentRepository repository, JdbcTemplate jdbcTemplate,
                                     List<IntentOutcomeApplier> appliers) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.appliersByType = appliers.stream()
                .collect(Collectors.toMap(IntentOutcomeApplier::intentType, Function.identity()));
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

    /**
     * Marks SENT, or does nothing if it already is.
     *
     * <p>For the retry path. An intent already at SENT means a previous attempt reached
     * the channel and we never learned the outcome, so the caller is about to re-issue
     * the same call with the same idempotency key — which is the designed-safe action,
     * not an error. {@link #markSent} rejects that transition (correctly, for the
     * first-attempt path), and using it on a retry turns every second attempt into an
     * IllegalStateException thrown <em>before</em> the call, which looks like a failure
     * of the channel rather than of our own bookkeeping.
     */
    @Transactional
    public void markSentIfPrepared(UUID intentId) {
        ChannelCallIntent intent = getOrThrow(intentId);
        if (intent.getStatus() == IntentStatus.PREPARED) {
            intent.markSent();
        }
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
     * resolve either is marked AMBIGUOUS and escalated to the operator queue (Plan §3:
     * "when no outcome can be established: AMBIGUOUS, then the operator queue") rather than left to retry forever.
     *
     * <p>Plan v5 §2.2, H3: resolving the intent is only half the job. A type with no
     * {@link IntentOutcomeApplier} registered is deliberately <em>not</em> marked
     * resolved — it goes AMBIGUOUS instead, the same as a channel that could not say —
     * because "resolved at the intent level, untouched at the domain level" is exactly
     * the silent state this whole mechanism exists to prevent.
     */
    @Transactional
    public int recoverStuckIntents(IntentStatusResolver resolver, java.time.Duration minAge) {
        Instant cutoff = Instant.now().minus(minAge.toMillis(), ChronoUnit.MILLIS);
        List<ChannelCallIntent> stuck = repository.findByStatusAndUpdatedAtBefore(IntentStatus.SENT, cutoff);

        int resolved = 0;
        for (ChannelCallIntent intent : stuck) {
            Optional<String> response = resolver.queryStatus(intent);
            IntentOutcomeApplier applier = appliersByType.get(intent.getType());

            if (response.isPresent() && applier != null) {
                intent.recordResult(response.get());
                applier.apply(intent, response.get());
                resolved++;
            } else {
                markAmbiguousAndEscalate(intent, response.isPresent());
            }
        }
        return resolved;
    }

    private void markAmbiguousAndEscalate(ChannelCallIntent intent, boolean channelResolvedIt) {
        intent.markAmbiguous();
        escalateToOperatorQueue(intent);
        if (channelResolvedIt) {
            log.warn("Intent {} (type={}, targetReference={}) has no registered IntentOutcomeApplier — "
                            + "escalated instead of applying a domain effect nothing declared",
                    intent.getId(), intent.getType(), intent.getTargetReference());
        } else {
            log.warn("Intent {} (type={}, targetReference={}) is AMBIGUOUS after durumSorgula — escalated",
                    intent.getId(), intent.getType(), intent.getTargetReference());
        }
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

    /**
     * The existing intent for an action, if there is one.
     *
     * <p>Needed by retry paths: a second attempt at the same action must reuse the first
     * attempt's intent id, because that id is the channel's idempotency key. Minting a
     * new one would present the retry to the channel as a brand-new request — a second
     * shipping label, a second refund.
     */
    @Transactional(readOnly = true)
    public Optional<ChannelCallIntent> findByTarget(UUID organizationId, String type, UUID targetReference) {
        return repository.findByOrganizationIdAndTypeAndTargetReference(organizationId, type, targetReference);
    }

    private ChannelCallIntent getOrThrow(UUID intentId) {
        return repository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("No channel_call_intent with id " + intentId));
    }
}
