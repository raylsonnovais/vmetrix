package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.application.sql.GeneratedSql;
import com.vmetrix.querymanager.application.sql.SqlGenerator;
import com.vmetrix.querymanager.application.validation.QueryValidator;
import com.vmetrix.querymanager.application.validation.ValidatedQuery;
import com.vmetrix.querymanager.application.validation.ValidationError;
import com.vmetrix.querymanager.application.validation.ValidationResult;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.query.QueryRequest;
import com.vmetrix.querymanager.infrastructure.metadata.MetadataCatalogProvider;
import com.vmetrix.querymanager.infrastructure.rest.dto.BuildResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ExecuteResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationErrorResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.ValidationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public QueryController(MetadataCatalogProvider catalogProvider, QueryValidator validator,
                           SqlGenerator sqlGenerator, NamedParameterJdbcTemplate jdbcTemplate) {
        this.catalogProvider = catalogProvider;
        this.validator = validator;
        this.sqlGenerator = sqlGenerator;
        this.jdbcTemplate = jdbcTemplate;
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

    /** Bonus: build the SQL through the same path, then run it against the database and return the rows. */
    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody QueryRequest request) {
        MetadataCatalog catalog = catalogProvider.current();
        ValidationResult result = validator.validate(request, catalog);
        if (!result.isValid()) {
            return ResponseEntity.badRequest().body(toValidationResponse(result));
        }
        ValidatedQuery validated = result.validatedQuery().orElseThrow();
        GeneratedSql generated = sqlGenerator.generate(validated, catalog);

        // Name each output column by its alias, else its logical name — never the raw physical column.
        List<String> columns = validated.select().stream()
                .map(field -> field.outputAlias() != null ? field.outputAlias() : field.field().name())
                .toList();
        List<Map<String, Object>> rows = jdbcTemplate.query(generated.sql(), generated.parameters(),
                (rs, rowNum) -> toRow(rs, columns));

        return ResponseEntity.ok(new ExecuteResponse(toBuildResponse(generated), rows.size(), rows));
    }

    private static Map<String, Object> toRow(ResultSet rs, List<String> columns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), rs.getObject(i + 1)); // by position: the SELECT order matches the select list
        }
        return row;
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
