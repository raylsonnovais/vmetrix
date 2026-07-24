package com.vmetrix.querymanager.domain.query;

import java.util.Optional;

/**
 * The closed set of semantic comparators a filter condition can use (spec 5.1 / 5.3).
 *
 * <p>Each constant knows its {@link #wireName() API name} and its {@link #cardinality() value shape}
 * (how many values it binds). The cardinality drives validation (is the supplied value well formed?)
 * and, in the SQL layer, how many named bind parameters to allocate. Which comparators are valid for
 * which data type is <em>not</em> encoded here — that lives in the {@code META_COMPARATOR} matrix so
 * it stays configurable.
 *
 * <p>The SQL fragment each comparator renders is added as a Strategy in the SQL-generation phase;
 * this enum deliberately stays free of SQL text so the domain has no dialect knowledge.
 */
public enum Comparator {

    EQUALS("equals", ValueCardinality.SINGLE),
    NOT_EQUALS("notEquals", ValueCardinality.SINGLE),
    GREATER_THAN("greaterThan", ValueCardinality.SINGLE),
    LESS_THAN("lessThan", ValueCardinality.SINGLE),
    GREATER_OR_EQUAL("greaterOrEqual", ValueCardinality.SINGLE),
    LESS_OR_EQUAL("lessOrEqual", ValueCardinality.SINGLE),
    IN("in", ValueCardinality.LIST),
    NOT_IN("notIn", ValueCardinality.LIST),
    BETWEEN("between", ValueCardinality.RANGE),
    LIKE("like", ValueCardinality.SINGLE),
    IS_NULL("isNull", ValueCardinality.NONE),
    IS_NOT_NULL("isNotNull", ValueCardinality.NONE);

    /** How many values a comparator expects, i.e. how many bind parameters it allocates. */
    public enum ValueCardinality {
        /** No value: {@code isNull}, {@code isNotNull}. */
        NONE,
        /** Exactly one value: {@code equals}, {@code greaterThan}, {@code like}, ... */
        SINGLE,
        /** A non-empty list of values: {@code in}, {@code notIn}. */
        LIST,
        /** Exactly two values (lower, upper): {@code between}. */
        RANGE
    }

    private final String wireName;
    private final ValueCardinality cardinality;

    Comparator(String wireName, ValueCardinality cardinality) {
        this.wireName = wireName;
        this.cardinality = cardinality;
    }

    /** The name used in the API/metadata (e.g. {@code "greaterThan"}). */
    public String wireName() {
        return wireName;
    }

    /** The value shape this comparator expects. */
    public ValueCardinality cardinality() {
        return cardinality;
    }

    /** Resolves an API comparator name to a constant, empty if unknown. */
    public static Optional<Comparator> fromWire(String wireName) {
        for (Comparator comparator : values()) {
            if (comparator.wireName.equals(wireName)) {
                return Optional.of(comparator);
            }
        }
        return Optional.empty();
    }
}
