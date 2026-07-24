package com.vmetrix.querymanager.application.join;

/**
 * Thrown when a set of referenced entities cannot be turned into an unambiguous join plan — for
 * example when no single root reaches them all, or when a base entity is referenced that could be
 * reached by more than one relation (e.g. {@code party} directly, reachable via both
 * {@code counterparty} and {@code issuer}). The resolver refuses rather than guessing a path.
 */
public class JoinResolutionException extends RuntimeException {

    public JoinResolutionException(String message) {
        super(message);
    }
}
