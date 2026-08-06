package com.ecommercehub.domain.intent;

/**
 * Plan §3 kanal_cagri_niyeti.durum: PREPARED (HAZIRLANDI) is committed BEFORE the
 * channel call happens; SENT (GONDERILDI) is committed BEFORE the call is actually
 * made — the boundary a crash between "call sent" and "result recorded" leaves the
 * row at. RESULT_RECEIVED (SONUC_ALINDI) is the normal end state. AMBIGUOUS
 * (BELIRSIZ) means even durumSorgula couldn't resolve it — it escalates to the
 * operator queue instead of retrying blindly.
 */
public enum IntentStatus {
    PREPARED,
    SENT,
    RESULT_RECEIVED,
    AMBIGUOUS
}
