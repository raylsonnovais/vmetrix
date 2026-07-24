package com.vmetrix.querymanager.domain.metadata;

/**
 * Immutable description of a relationship (a JOIN edge) declared in {@code META_RELATION}.
 *
 * <p>The engine models the <em>relationship</em> as the graph edge, not the table. This is the key
 * design decision behind automatic JOIN resolution: {@code counterparty} and {@code issuer} both
 * target {@code party}, yet they are distinct edges with distinct SQL aliases, so a query using both
 * makes {@code PARTY} appear twice in the generated SQL.
 *
 * <p>The {@link #alias()} is also the query-facing entity name a caller uses to reach the target
 * (e.g. {@code {"entity": "counterparty", "field": "partyName"}}).
 *
 * @param alias        query-facing name of this edge (e.g. {@code "counterparty"})
 * @param sourceEntity logical entity the edge starts from (e.g. {@code "transaction"})
 * @param sourceField  logical field on the source entity (e.g. {@code "counterpartyId"})
 * @param targetEntity logical entity the edge points to (e.g. {@code "party"})
 * @param targetField  logical field on the target entity (e.g. {@code "partyId"})
 * @param joinType     the SQL join flavour to use for this edge
 */
public record RelationMeta(
        String alias,
        String sourceEntity,
        String sourceField,
        String targetEntity,
        String targetField,
        JoinType joinType) {
}
