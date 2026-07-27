package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.application.join.JoinResolutionException;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationErrorResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Maps failures to HTTP responses in one consistent shape — the same {@link ValidationResponse}
 * ({@code valid:false, errors:[...]}) every endpoint uses for validation errors — so a client needs a
 * single parser. Stack traces and framework internals never reach the response body.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's protocol exceptions keep their
 * correct status (405 for the wrong method, 415 for an unsupported media type, ...) instead of being
 * swallowed by the catch-all and returned as 500. {@link #handleExceptionInternal} re-bodies all of
 * those into the common error shape; only genuinely unexpected exceptions become a logged 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * An ambiguous/unreachable combination of referenced entities is invalid user input, not a server
     * fault — only the resolver can detect it (it depends on the combination), so it surfaces as a 400.
     */
    @ExceptionHandler(JoinResolutionException.class)
    public ResponseEntity<Object> handleJoinResolution(JoinResolutionException exception, WebRequest request) {
        return handleExceptionInternal(exception, error(exception.getMessage()),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /** Anything unexpected: a generic 500 with no details leaked; the cause is logged server-side. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        log.error("Unexpected error handling request", exception);
        return handleExceptionInternal(exception, error("An unexpected internal error occurred"),
                new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** Malformed JSON, or a filter node that is neither a group nor a condition: a clear 400. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(exception, error(safeMessage(exception.getMostSpecificCause())),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Single funnel for every response body. Protocol exceptions handled by the base class arrive with a
     * {@code null} body; we replace it with the common {@link ValidationResponse} shape.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatus status, WebRequest request) {
        Object responseBody = body != null ? body : error(messageFor(exception, status));
        return super.handleExceptionInternal(exception, responseBody, headers, status, request);
    }

    private static ValidationResponse error(String message) {
        return ValidationResponse.failure(List.of(new ValidationErrorResponse(null, null, null, message)));
    }

    private static String messageFor(Exception exception, HttpStatus status) {
        return exception.getMessage() != null ? exception.getMessage() : status.getReasonPhrase();
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
