package com.vmetrix.querymanager.domain.metadata;

import com.vmetrix.querymanager.domain.query.Comparator;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, in-memory view of the whole metadata model: entities, relations and the
 * comparator-by-type matrix. It is the single source of truth the engine consults, and the
 * structural whitelist that keeps caller-supplied names out of the generated SQL.
 *
 * <p>Built once at startup from the {@code META_*} tables and swapped atomically on reload, so an
 * instance is safe to share across requests. Lookups take a <em>query-facing name</em>, which may
 * be either a base entity ({@code transaction}, {@code instrument}) or a relation alias
 * ({@code counterparty}, {@code issuer}); {@link #resolveTargetEntity(String)} unifies the two.
 */
public interface MetadataCatalog {

    /** All entities, in a stable order. */
    List<EntityMeta> entities();

    /** All relations, in a stable order. */
    List<RelationMeta> relations();

    /** Finds a base entity by its logical name; empty if no such entity exists. */
    Optional<EntityMeta> findEntity(String name);

    /** Finds a relation by its alias; empty if no such relation exists. */
    Optional<RelationMeta> findRelation(String alias);

    /**
     * Resolves a query-facing name to the entity whose fields it exposes: the entity itself if
     * {@code name} is a base entity, or the relation's target entity if {@code name} is a relation
     * alias. Empty if the name is neither.
     */
    Optional<EntityMeta> resolveTargetEntity(String name);

    /** The comparators declared valid for a data type, as a stable set. */
    Set<Comparator> comparatorsFor(DataType type);

    /** Whether the comparator is valid for the given data type per the metadata matrix. */
    boolean supports(DataType type, Comparator comparator);
}
