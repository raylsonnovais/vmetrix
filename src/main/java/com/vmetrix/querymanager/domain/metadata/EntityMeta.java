package com.vmetrix.querymanager.domain.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Immutable description of an entity, as declared in {@code META_ENTITY} plus its
 * {@link FieldMeta fields} and outgoing {@link RelationMeta relations}.
 *
 * @param name          logical entity name exposed by the API (e.g. {@code "transaction"})
 * @param physicalTable physical table name (e.g. {@code "TRANSACTION"})
 * @param defaultAlias  default SQL alias for this table (e.g. {@code "t"})
 * @param description   human-readable description, may be {@code null}
 * @param fields        the entity's fields, in declaration order
 * @param relations     the relations whose source is this entity, in declaration order
 */
public record EntityMeta(
        String name,
        String physicalTable,
        String defaultAlias,
        String description,
        List<FieldMeta> fields,
        List<RelationMeta> relations) {

    /** Defensive copies keep the aggregate immutable regardless of how it was built. */
    public EntityMeta {
        fields = List.copyOf(fields);
        relations = List.copyOf(relations);
    }

    /** Finds a field by its logical camelCase name; empty if this entity has no such field. */
    public Optional<FieldMeta> findField(String logicalName) {
        return fields.stream().filter(f -> f.name().equals(logicalName)).findFirst();
    }
}
