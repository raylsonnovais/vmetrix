package com.vmetrix.querymanager.domain.metadata;

import java.util.Optional;

/**
 * SQL join flavour declared on a relation ({@code META_RELATION.JOIN_TYPE}).
 *
 * <p>The sample model only uses {@link #LEFT}, but the full set is recognised so the metadata can
 * describe other relationships without a code change.
 */
public enum JoinType {

    LEFT("LEFT JOIN"),
    INNER("INNER JOIN"),
    RIGHT("RIGHT JOIN"),
    FULL("FULL JOIN");

    private final String sqlKeyword;

    JoinType(String sqlKeyword) {
        this.sqlKeyword = sqlKeyword;
    }

    /** The SQL keyword emitted for this join, e.g. {@code "LEFT JOIN"}. */
    public String sqlKeyword() {
        return sqlKeyword;
    }

    /** Resolves a metadata token (e.g. {@code "LEFT"} or {@code "LEFT JOIN"}), empty if unknown. */
    public static Optional<JoinType> fromWire(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.trim().toUpperCase().replace(" JOIN", "");
        for (JoinType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
