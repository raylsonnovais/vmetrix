package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.application.join.JoinResolutionException;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationErrorResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps failures to HTTP responses in one consistent shape — the same {@link ValidationResponse}
 * ({@code valid:false, errors:[...]}) every endpoint uses for validation errors — so a client needs a
 * single parser. Stack traces and framework internals never reach the response body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * An ambiguous/unreachable combination of referenced entities is invalid user input, not a server
     * fault — only the resolver can detect it (it depends on the combination), so it surfaces here as a
     * structured 400.
     */
    @ExceptionHandler(JoinResolutionException.class)
    public ResponseEntity<ValidationResponse> handleJoinResolution(JoinResolutionException exception) {
        return badRequest(exception.getMessage());
    }

    /** Malformed JSON, or a filter node that is neither a group nor a condition: a clear 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ValidationResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return badRequest(safeMessage(exception.getMostSpecificCause()));
    }

    /** Anything unexpected: a generic 500 with no details leaked; the cause is logged server-side. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ValidationResponse> handleUnexpected(Exception exception) {
        log.error("Unexpected error handling request", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("An unexpected internal error occurred"));
    }

    private static ResponseEntity<ValidationResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(error(message));
    }

    private static ValidationResponse error(String message) {
        return ValidationResponse.failure(List.of(new ValidationErrorResponse(null, null, null, message)));
    }

    /** Uses our own clear deserializer messages while trimming Jackson's input-echo/location suffix. */
    private static String safeMessage(Throwable cause) {
        if (cause == null || cause.getMessage() == null) {
            return "Malformed request body";
        }
        String message = cause.getMessage();
        int locationMarker = message.indexOf(" at [Source");
        return locationMarker >= 0 ? message.substring(0, locationMarker) : message;
    }
}
