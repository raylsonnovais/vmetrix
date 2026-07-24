package com.vmetrix.querymanager.domain.query;

import java.util.Optional;

/** Boolean operator joining the children of a {@link FilterGroup}. */
public enum LogicalOperator {

    AND,
    OR;

    /** Resolves an API operator token (case-insensitive), empty if unknown. */
    public static Optional<LogicalOperator> fromWire(String token) {
        if (token == null) {
            return Optional.empty();
        }
        for (LogicalOperator operator : values()) {
            if (operator.name().equalsIgnoreCase(token)) {
                return Optional.of(operator);
            }
        }
        return Optional.empty();
    }
}
