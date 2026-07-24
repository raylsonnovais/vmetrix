package com.vmetrix.querymanager.domain.metadata;

import java.util.Optional;

/**
 * Logical data type of a field, as declared in the metadata ({@code META_FIELD.DATA_TYPE}).
 *
 * <p>The type drives two things: which comparators are valid for the field (the comparator matrix
 * in {@code META_COMPARATOR}) and how a raw filter value is converted before it becomes a bind
 * parameter. Types are a closed set here, but the set of fields carrying each type is fully
 * metadata-driven.
 */
public enum DataType {

    STRING("string"),
    NUMBER("number"),
    DATE("date"),
    TIMESTAMP("timestamp");

    private final String wireName;

    DataType(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase name used in the metadata and in the API (e.g. {@code "string"}). */
    public String wireName() {
        return wireName;
    }

    /** Resolves a metadata/API type name to a {@link DataType}, empty if unknown. */
    public static Optional<DataType> fromWire(String wireName) {
        for (DataType type : values()) {
            if (type.wireName.equalsIgnoreCase(wireName)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
