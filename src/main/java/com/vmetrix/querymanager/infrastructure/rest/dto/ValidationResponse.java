package com.vmetrix.querymanager.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body of {@code POST /api/query/validate} (spec 5.4). On success the payload is just
 * {@code {"valid": true}}; on failure it is {@code {"valid": false, "errors": [...]}} — the
 * {@code errors} array is omitted when the query is valid.
 *
 * @param valid  whether the query passed validation
 * @param errors accumulated errors, or {@code null} when valid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationResponse(boolean valid, List<ValidationErrorResponse> errors) {

    /** The success payload: {@code {"valid": true}}. */
    public static ValidationResponse success() {
        return new ValidationResponse(true, null);
    }

    /** The failure payload carrying all accumulated errors. */
    public static ValidationResponse failure(List<ValidationErrorResponse> errors) {
        return new ValidationResponse(false, errors);
    }
}
