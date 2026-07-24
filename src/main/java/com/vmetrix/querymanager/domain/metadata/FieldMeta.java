package com.vmetrix.querymanager.domain.metadata;

import java.util.Optional;

/**
 * Immutable description of a single field of an entity, as declared in {@code META_FIELD}.
 *
 * <p>This is the heart of the logical-to-physical mapping and of the security model: the API speaks
 * in {@link #name() logical camelCase names}, while only the {@link #physicalName() physical column}
 * from this record is ever written into SQL. The camelCase-to-SNAKE_CASE translation is therefore a
 * metadata lookup, never a string transformation (which would let a caller inject an identifier).
 *
 * @param name         logical camelCase name exposed by the API (e.g. {@code "txnDate"})
 * @param physicalName physical SNAKE_CASE column name (e.g. {@code "TXN_DATE"})
 * @param dataType     logical data type, driving comparator validity and value conversion
 * @param primaryKey   whether this field is (part of) the primary key
 * @param foreignKey   the target of the FK if this field references another entity, else empty
 * @param filterable   whether the field may appear in a filter condition
 * @param selectable   whether the field may be projected in the select list
 */
public record FieldMeta(
        String name,
        String physicalName,
        DataType dataType,
        boolean primaryKey,
        Optional<ForeignKey> foreignKey,
        boolean filterable,
        boolean selectable) {

    /**
     * Reference from a foreign-key field to the field it points at, using logical names.
     *
     * @param targetEntity logical entity name the FK points to (e.g. {@code "party"})
     * @param targetField  logical field on the target entity (e.g. {@code "partyId"})
     */
    public record ForeignKey(String targetEntity, String targetField) {
    }
}
