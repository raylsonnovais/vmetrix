package com.vmetrix.querymanager.domain.query;

/**
 * One projected column of a query, in logical vocabulary.
 *
 * @param entity query-facing entity name (base entity or relation alias, e.g. {@code "counterparty"})
 * @param field  logical camelCase field name (e.g. {@code "partyName"})
 * @param alias  optional output column alias; {@code null} if the caller did not supply one
 */
public record SelectField(String entity, String field, String alias) {
}
