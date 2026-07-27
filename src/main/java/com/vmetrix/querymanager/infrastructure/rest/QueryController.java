package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.application.sql.GeneratedSql;
import com.vmetrix.querymanager.application.sql.SqlGenerator;
import com.vmetrix.querymanager.application.validation.QueryValidator;
import com.vmetrix.querymanager.application.validation.ValidationError;
import com.vmetrix.querymanager.application.validation.ValidationResult;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.query.QueryRequest;
import com.vmetrix.querymanager.infrastructure.metadata.MetadataCatalogProvider;
import com.vmetrix.querymanager.infrastructure.rest.dto.BuildResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationErrorResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Query endpoints. Thin orchestration only: read the current catalog, validate, and either report the
 * accumulated errors (same shape as {@code /validate}) or generate the SQL. No business logic lives here.
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final MetadataCatalogProvider catalogProvider;
    private final QueryValidator validator;
    private final SqlGenerator sqlGenerator;

    public QueryController(MetadataCatalogProvider catalogProvider, QueryValidator validator, SqlGenerator sqlGenerator) {
        this.catalogProvider = catalogProvider;
        this.validator = validator;
        this.sqlGenerator = sqlGenerator;
    }

    @PostMapping("/build")
    public ResponseEntity<?> build(@RequestBody QueryRequest request) {
        MetadataCatalog catalog = catalogProvider.current();
        ValidationResult result = validator.validate(request, catalog);
        if (!result.isValid()) {
            return ResponseEntity.badRequest().body(toValidationResponse(result));
        }
        GeneratedSql generated = sqlGenerator.generate(result.validatedQuery().orElseThrow(), catalog);
        return ResponseEntity.ok(toBuildResponse(generated));
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResponse> validate(@RequestBody QueryRequest request) {
        ValidationResult result = validator.validate(request, catalogProvider.current());
        if (result.isValid()) {
            return ResponseEntity.ok(ValidationResponse.success());
        }
        return ResponseEntity.badRequest().body(toValidationResponse(result));
    }

    private static BuildResponse toBuildResponse(GeneratedSql generated) {
        return new BuildResponse(
                generated.sql(),
                generated.parameters(),
                generated.resolvedTables(),
                generated.resolvedJoins(),
                new BuildResponse.QueryMetadata(generated.columnCount(), generated.filterCount(), nowIsoUtc()));
    }

    private static ValidationResponse toValidationResponse(ValidationResult result) {
        List<ValidationErrorResponse> errors = result.errors().stream()
                .map(QueryController::toErrorResponse)
                .toList();
        return ValidationResponse.failure(errors);
    }

    private static ValidationErrorResponse toErrorResponse(ValidationError error) {
        return new ValidationErrorResponse(error.entity(), error.field(), error.comparator(), error.message());
    }

    /** The generation timestamp is stamped here, at the REST boundary, keeping the generator time-free. */
    private static String nowIsoUtc() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
