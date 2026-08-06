package com.ecommercehub.domain.auth;

/**
 * The caller is authenticated but not authorised for this action.
 *
 * <p>Lives in the domain, not the web layer, because the checks it backs do too: a
 * return approval enforces OPERATOR inside the service that performs it, so the rule
 * holds no matter which entry point calls it — an HTTP endpoint today, a task handler
 * or a batch import tomorrow. A check that only exists in a controller is a check that
 * the second caller silently skips.
 */
public class InsufficientRoleException extends RuntimeException {

    private final HubRole actual;
    private final HubRole required;

    public InsufficientRoleException(HubRole actual, HubRole required) {
        super("Role " + actual + " is not sufficient — " + required + " or higher is required");
        this.actual = actual;
        this.required = required;
    }

    public HubRole getActual() {
        return actual;
    }

    public HubRole getRequired() {
        return required;
    }
}
