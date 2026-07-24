package com.vmetrix.querymanager.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One error entry in a validation response (spec 5.4). The locating fields are omitted from JSON when
 * absent, so an error can carry just {@code entity + message} or the full {@code entity + field +
 * comparator + message}.
 *
 * @param entity     offending entity, or {@code null}
 * @param field      offending field, or {@code null}
 * @param comparator offending comparator, or {@code null}
 * @param message    human-readable explanation (always present)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorResponse(String entity, String field, String comparator, String message) {
}
