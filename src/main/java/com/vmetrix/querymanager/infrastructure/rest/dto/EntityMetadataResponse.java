package com.vmetrix.querymanager.infrastructure.rest.dto;

import java.util.List;

/**
 * One entity in the {@code GET /api/metadata/entities} response (spec 5.2): its physical mapping, its
 * fields and its outgoing relations, projected straight from the catalog.
 *
 * @param entity        logical entity name
 * @param physicalTable physical table name
 * @param alias         default SQL alias
 * @param fields        the entity's fields
 * @param relations     the entity's outgoing relations
 */
public record EntityMetadataResponse(
        String entity,
        String physicalTable,
        String alias,
        List<FieldResponse> fields,
        List<RelationResponse> relations) {

    /**
     * @param name         logical camelCase name
     * @param physicalName physical column name
     * @param type         data type wire name (string/number/date/timestamp)
     * @param primaryKey   whether the field is (part of) the primary key
     * @param filterable   whether the field may be filtered on
     * @param selectable   whether the field may be projected
     */
    public record FieldResponse(
            String name,
            String physicalName,
            String type,
            boolean primaryKey,
            boolean filterable,
            boolean selectable) {
    }

    /**
     * @param alias        relation (edge) alias, also the query-facing entity name
     * @param targetEntity entity this relation points to
     * @param joinType     join flavour (e.g. {@code "LEFT"})
     * @param sourceField  logical source field
     * @param targetField  logical target field
     */
    public record RelationResponse(
            String alias,
            String targetEntity,
            String joinType,
            String sourceField,
            String targetField) {
    }
}
