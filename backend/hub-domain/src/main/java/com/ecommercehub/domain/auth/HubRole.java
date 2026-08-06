package com.ecommercehub.domain.auth;

/**
 * plan §3: the three roles, in increasing order of authority. Kept as an enum rather
 * than free-text role names so a typo in a check is a compile error instead of a
 * permission that silently never matches — the failure mode of string-compared roles
 * is an authorization rule that looks present and denies nothing.
 */
public enum HubRole {

    /** Read-only. Explicitly cannot approve returns (plan Faz 5 gate). */
    OBSERVER,

    /** Day-to-day operations: return approval and rejection, operator queue work. */
    OPERATOR,

    /** Everything OPERATOR can do, plus refunds and user administration (plan §7). */
    ADMIN;

    /** Spring Security's convention: authorities carry a ROLE_ prefix, role names do not. */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean atLeast(HubRole required) {
        return ordinal() >= required.ordinal();
    }
}
