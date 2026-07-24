package com.vmetrix.querymanager.application.validation;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of validating a {@code QueryRequest}: either a {@link ValidatedQuery} or a non-empty list
 * of {@link ValidationError}s. Errors are <em>accumulated</em> (the validator does not stop at the
 * first problem), so a caller sees every issue at once — as required by the {@code /validate} contract.
 *
 * <p>Modelled as a small tagged value with static factories rather than throwing, because the invalid
 * case is an expected, first-class result that both {@code /build} and {@code /validate} report.
 */
public final class ValidationResult {

    private final ValidatedQuery validatedQuery;
    private final List<ValidationError> errors;

    private ValidationResult(ValidatedQuery validatedQuery, List<ValidationError> errors) {
        this.validatedQuery = validatedQuery;
        this.errors = errors;
    }

    /** Builds a successful result wrapping the validated query. */
    public static ValidationResult valid(ValidatedQuery validatedQuery) {
        return new ValidationResult(validatedQuery, List.of());
    }

    /** Builds a failed result carrying all accumulated errors (must be non-empty). */
    public static ValidationResult invalid(List<ValidationError> errors) {
        return new ValidationResult(null, List.copyOf(errors));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    /** The validated query, present only when {@link #isValid()} is {@code true}. */
    public Optional<ValidatedQuery> validatedQuery() {
        return Optional.ofNullable(validatedQuery);
    }

    /** The accumulated errors, empty when valid. */
    public List<ValidationError> errors() {
        return errors;
    }
}
