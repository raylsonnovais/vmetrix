package com.vmetrix.querymanager.domain.query;

import java.util.Optional;

/** Sort direction for an {@code ORDER BY} term. */
public enum SortDirection {

    ASC("asc"),
    DESC("desc");

    private final String wireName;

    SortDirection(String wireName) {
        this.wireName = wireName;
    }

    /** The keyword emitted in SQL ({@code ASC} / {@code DESC}). */
    public String sqlKeyword() {
        return name();
    }

    /** Resolves an API direction token (case-insensitive), empty if unknown. */
    public static Optional<SortDirection> fromWire(String token) {
        if (token == null) {
            return Optional.empty();
        }
        for (SortDirection direction : values()) {
            if (direction.wireName.equalsIgnoreCase(token)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }
}
