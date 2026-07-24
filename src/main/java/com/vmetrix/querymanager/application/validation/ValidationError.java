package com.vmetrix.querymanager.application.validation;

/**
 * A single, structured validation problem. Mirrors the error shape in the API contract (spec 5.4):
 * the locating fields are optional so an error can point at an entity, a field, a comparator, or none
 * of them, while {@code message} is always present.
 *
 * @param entity     offending entity name, or {@code null} if not applicable
 * @param field      offending field name, or {@code null} if not applicable
 * @param comparator offending comparator name, or {@code null} if not applicable
 * @param message    human-readable explanation (always present)
 */
public record ValidationError(String entity, String field, String comparator, String message) {

    /** Error not tied to a specific location (e.g. "select must not be empty"). */
    public static ValidationError of(String message) {
        return new ValidationError(null, null, null, message);
    }

    /** Error about an unknown or unusable entity. */
    public static ValidationError forEntity(String entity, String message) {
        return new ValidationError(entity, null, null, message);
    }

    /** Error about a specific field of an entity. */
    public static ValidationError forField(String entity, String field, String message) {
        return new ValidationError(entity, field, null, message);
    }

    /** Error about a comparator used against a field. */
    public static ValidationError forComparator(String entity, String field, String comparator, String message) {
        return new ValidationError(entity, field, comparator, message);
    }
}
