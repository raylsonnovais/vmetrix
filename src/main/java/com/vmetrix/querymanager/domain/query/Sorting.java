package com.vmetrix.querymanager.domain.query;

/**
 * One {@code ORDER BY} term, in logical vocabulary.
 *
 * @param entity    query-facing entity name (base entity or relation alias)
 * @param field     logical camelCase field name
 * @param direction sort direction
 */
public record Sorting(String entity, String field, SortDirection direction) {
}
