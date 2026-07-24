package com.vmetrix.querymanager.domain.query;

/**
 * Leaf node: a single comparison of a field against a value.
 *
 * <p>The {@code value} is the raw, untrusted payload as received from the request (a scalar, a list
 * for {@code in}/{@code notIn}, a two-element list for {@code between}, or {@code null} for the
 * null-checks). It is validated and converted according to the field's data type before it ever
 * becomes a bind parameter; it is never written into the SQL text.
 *
 * @param entity     query-facing entity name (base entity or relation alias)
 * @param field      logical camelCase field name
 * @param comparator the semantic comparator to apply
 * @param value      raw filter value, shape depends on the comparator's cardinality
 */
public record FilterCondition(String entity, String field, Comparator comparator, Object value)
        implements FilterNode {
}
