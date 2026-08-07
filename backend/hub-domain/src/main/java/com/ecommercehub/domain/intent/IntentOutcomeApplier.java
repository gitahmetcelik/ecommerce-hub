package com.ecommercehub.domain.intent;

/**
 * What a recovered intent means for the domain it belongs to (Plan v5 §2.2, H3).
 *
 * <p>{@link ChannelCallIntentService#recoverStuckIntents} resolving a SENT intent to
 * RESULT_RECEIVED only says the channel finished the call — it says nothing about the
 * domain row (return_payment, shipment, ...) the intent was acting on behalf of.
 * Before this existed, that row was left exactly as it was before the call, which is
 * how a refund the channel confirms as paid keeps reading PENDING and a second one
 * gets authorised on top of it. Every intent type that can get stuck at SENT needs an
 * applier registered for it; a type with none is deliberately left AMBIGUOUS rather
 * than silently marked resolved with no domain effect.
 */
public interface IntentOutcomeApplier {

    /** The {@link ChannelCallIntent#getType()} this applier handles. */
    String intentType();

    /** Applies the recovered outcome to whatever domain row {@code intent} points at. */
    void apply(ChannelCallIntent intent, String channelResponseJson);
}
