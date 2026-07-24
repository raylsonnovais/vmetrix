package com.vmetrix.querymanager.application.validation;

import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.query.QueryRequest;

/**
 * Validates a {@link QueryRequest} against a {@link MetadataCatalog}, producing either a
 * {@link ValidatedQuery} or an accumulated list of {@link ValidationError}s.
 *
 * <p>Responsibilities (all metadata-driven; no rule is hardcoded per entity):
 * <ul>
 *   <li>resolve every entity/field name — unknown ones are reported, not guessed;</li>
 *   <li>enforce the {@code selectable}/{@code filterable} flags;</li>
 *   <li>check each comparator against the field's data type via the comparator matrix;</li>
 *   <li>convert each value to its typed form (e.g. ISO date, {@code BigDecimal}), reporting
 *       malformed values instead of passing them on;</li>
 *   <li>reject structurally empty inputs (no select columns, empty filter groups).</li>
 * </ul>
 *
 * <p>Validation <strong>accumulates</strong> all problems rather than failing fast.
 */
public interface QueryValidator {

    /**
     * Validates the request against the given catalog snapshot.
     *
     * @param request the untrusted query specification
     * @param catalog the metadata to validate against
     * @return a valid result carrying the {@link ValidatedQuery}, or an invalid result with all errors
     */
    ValidationResult validate(QueryRequest request, MetadataCatalog catalog);
}
