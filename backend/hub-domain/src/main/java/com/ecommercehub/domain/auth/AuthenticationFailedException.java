package com.ecommercehub.domain.auth;

/**
 * Wrong credentials, unknown account, or an account that cannot authenticate.
 *
 * <p>One exception for all three on purpose: a caller who can tell "no such user" from
 * "wrong password" can enumerate which email addresses have accounts, and a caller who
 * can tell "disabled" from "wrong password" learns that a valid account exists. The
 * message here is deliberately uninformative; the log line is where the detail goes.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
