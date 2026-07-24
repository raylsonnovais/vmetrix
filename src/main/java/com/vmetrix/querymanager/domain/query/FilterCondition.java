package com.vmetrix.querymanager.domain.query;

/**
 * Leaf node: a single comparison of a field against a value.
 *
 * <p>Every component is the raw, untrusted payload as received from the request: {@code entity} and
 * {@code field} are logical names to be resolved against the catalog, and {@code comparator} is the
 * comparator's <em>wire name</em> (e.g. {@code "greaterThan"}), not yet a {@link Comparator} — so an
 * unrecognised comparator is a validation error the engine reports, rather than something the type
 * system silently forbids at the boundary. {@code value} is the raw value (a scalar, a list for
 * {@code in}/{@code notIn}, a two-element list for {@code between}, or {@code null} for the
 * null-checks). All of it is validated and converted before it can become a bind parameter, and none
 * of it is ever written into the SQL text.
 *
 * @param entity     query-facing entity name (base entity or relation alias)
 * @param field      logical camelCase field name
 * @param comparator comparator wire name, resolved to {@link Comparator} during validation
 * @param value      raw filter value, shape depends on the comparator's cardinality
 */
public record FilterCondition(String entity, String field, String comparator, Object value)
        implements FilterNode {
}
