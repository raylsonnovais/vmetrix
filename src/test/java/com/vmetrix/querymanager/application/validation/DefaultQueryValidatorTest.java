package com.vmetrix.querymanager.application.validation;

import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedCondition;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedField;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedGroup;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta.ForeignKey;
import com.vmetrix.querymanager.domain.metadata.InMemoryMetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.JoinType;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import com.vmetrix.querymanager.domain.query.Comparator;
import com.vmetrix.querymanager.domain.query.FilterCondition;
import com.vmetrix.querymanager.domain.query.FilterGroup;
import com.vmetrix.querymanager.domain.query.FilterNode;
import com.vmetrix.querymanager.domain.query.LogicalOperator;
import com.vmetrix.querymanager.domain.query.QueryRequest;
import com.vmetrix.querymanager.domain.query.SelectField;
import com.vmetrix.querymanager.domain.query.SortDirection;
import com.vmetrix.querymanager.domain.query.Sorting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.vmetrix.querymanager.domain.metadata.DataType.DATE;
import static com.vmetrix.querymanager.domain.metadata.DataType.NUMBER;
import static com.vmetrix.querymanager.domain.metadata.DataType.STRING;
import static com.vmetrix.querymanager.domain.metadata.DataType.TIMESTAMP;
import static com.vmetrix.querymanager.domain.query.Comparator.BETWEEN;
import static com.vmetrix.querymanager.domain.query.Comparator.EQUALS;
import static com.vmetrix.querymanager.domain.query.Comparator.GREATER_OR_EQUAL;
import static com.vmetrix.querymanager.domain.query.Comparator.GREATER_THAN;
import static com.vmetrix.querymanager.domain.query.Comparator.IN;
import static com.vmetrix.querymanager.domain.query.Comparator.IS_NOT_NULL;
import static com.vmetrix.querymanager.domain.query.Comparator.IS_NULL;
import static com.vmetrix.querymanager.domain.query.Comparator.LESS_OR_EQUAL;
import static com.vmetrix.querymanager.domain.query.Comparator.LESS_THAN;
import static com.vmetrix.querymanager.domain.query.Comparator.LIKE;
import static com.vmetrix.querymanager.domain.query.Comparator.NOT_EQUALS;
import static com.vmetrix.querymanager.domain.query.Comparator.NOT_IN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultQueryValidator}, built against a hand-made catalog that includes a
 * non-selectable and a non-filterable field (which the production seed does not have) so the access
 * flags can be exercised in isolation.
 */
class DefaultQueryValidatorTest {

    private DefaultQueryValidator validator;
    private MetadataCatalog catalog;

    @BeforeEach
    void setUp() {
        validator = new DefaultQueryValidator(new ValueConverter());
        catalog = buildCatalog();
    }

    // --- name resolution ----------------------------------------------------

    @Test
    void reportsUnknownEntity() {
        ValidationResult result = validate(request(List.of(sel("unknownEntity", "x")), null));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.entity()).isEqualTo("unknownEntity");
            assertThat(error.message()).isEqualTo("Entity unknownEntity does not exist in the model");
        });
    }

    @Test
    void reportsUnknownField() {
        ValidationResult result = validate(request(List.of(sel("transaction", "nope")), null));

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.entity()).isEqualTo("transaction");
            assertThat(error.field()).isEqualTo("nope");
            assertThat(error.message()).contains("does not exist on entity transaction");
        });
    }

    @Test
    void reportsNonSelectableFieldInSelect() {
        ValidationResult result = validate(request(List.of(sel("transaction", "internalId")), null));

        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("Field internalId is not selectable"));
    }

    @Test
    void reportsNonFilterableFieldInFilter() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "secretFlag", "equals", "x")));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).isEqualTo("Field secretFlag is not filterable"));
    }

    // --- comparator × type --------------------------------------------------

    @Test
    void reportsUnknownComparator() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "amount", "frobnicate", 5)));

        assertThat(result.errors()).anySatisfy(error -> {
            assertThat(error.comparator()).isEqualTo("frobnicate");
            assertThat(error.message()).contains("is not a recognised comparator");
        });
    }

    @Test
    void reportsComparatorInvalidForType() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "status", "greaterThan", "x")));

        // message mirrors the spec 5.4 example exactly
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).isEqualTo("Comparator greaterThan is not valid for string fields"));
    }

    // --- value conversion ---------------------------------------------------

    @Test
    void convertsIsoDate() {
        ResolvedCondition condition = onlyCondition(validateValid(
                cond("transaction", "txnDate", "equals", "2026-01-10")));

        assertThat(condition.value()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void reportsMalformedDate() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "txnDate", "equals", "2026-13-99")));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("is not a valid ISO-8601 date"));
    }

    @Test
    void convertsNumber() {
        ResolvedCondition condition = onlyCondition(validateValid(
                cond("transaction", "amount", "greaterThan", 1000000)));

        assertThat(condition.value()).isEqualTo(new BigDecimal("1000000"));
    }

    @Test
    void reportsNonNumericValueForNumberField() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "amount", "equals", "abc")));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("is not a valid number"));
    }

    @Test
    void convertsIsoTimestamp() {
        ResolvedCondition condition = onlyCondition(validateValid(
                cond("transaction", "createdAt", "greaterThan", "2026-03-10T09:30:00")));

        assertThat(condition.value()).isEqualTo(LocalDateTime.of(2026, 3, 10, 9, 30, 0));
    }

    @Test
    void acceptsPlainDateForTimestampAsStartOfDay() {
        ResolvedCondition condition = onlyCondition(validateValid(
                cond("transaction", "createdAt", "greaterThan", "2026-03-10")));

        assertThat(condition.value()).isEqualTo(LocalDateTime.of(2026, 3, 10, 0, 0, 0));
    }

    // --- cardinality --------------------------------------------------------

    @Test
    void reportsEmptyInList() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "status", "in", List.of())));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("non-empty list"));
    }

    @Test
    void reportsBetweenWithWrongNumberOfValues() {
        assertThat(validate(request(List.of(sel("transaction", "status")),
                cond("transaction", "amount", "between", List.of(1)))).errors())
                .anySatisfy(error -> assertThat(error.message()).contains("exactly two values"));

        assertThat(validate(request(List.of(sel("transaction", "status")),
                cond("transaction", "amount", "between", List.of(1, 2, 3)))).errors())
                .anySatisfy(error -> assertThat(error.message()).contains("exactly two values"));
    }

    @Test
    void reportsValueSuppliedToNullCheck() {
        ValidationResult result = validate(request(
                List.of(sel("transaction", "status")),
                cond("transaction", "status", "isNull", "x")));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("does not take a value"));
    }

    @Test
    void acceptsBetweenWithTwoValues() {
        ResolvedCondition condition = onlyCondition(validateValid(
                cond("transaction", "amount", "between", List.of(100, 200))));

        assertThat(condition.value()).isEqualTo(List.of(new BigDecimal("100"), new BigDecimal("200")));
    }

    // --- structure ----------------------------------------------------------

    @Test
    void reportsEmptySelect() {
        ValidationResult result = validate(request(List.of(), null));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).isEqualTo("select must contain at least one field"));
    }

    @Test
    void reportsEmptyNestedGroup() {
        FilterNode filter = group(LogicalOperator.AND,
                cond("transaction", "status", "equals", "SETTLED"),
                group(LogicalOperator.OR));

        ValidationResult result = validate(request(List.of(sel("transaction", "status")), filter));

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("must contain at least one condition"));
    }

    // --- accumulation -------------------------------------------------------

    @Test
    void accumulatesEveryError() {
        FilterNode filter = cond("transaction", "status", "greaterThan", "x"); // invalid comparator for string
        QueryRequest request = new QueryRequest(
                List.of(sel("unknownEntity", "x"), sel("transaction", "nope")),
                filter, List.of(), null);

        ValidationResult result = validate(request);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSize(3);
        assertThat(result.errors()).extracting(ValidationError::message)
                .anyMatch(m -> m.contains("Entity unknownEntity does not exist"))
                .anyMatch(m -> m.contains("does not exist on entity transaction"))
                .anyMatch(m -> m.contains("is not valid for string fields"));
    }

    // --- maxResults ---------------------------------------------------------

    @Test
    void appliesDefaultMaxResultsWhenOmitted() {
        ValidatedQuery query = validateValid(cond("transaction", "status", "equals", "SETTLED"));
        assertThat(query.maxResults()).isEqualTo(100);
    }

    @Test
    void keepsMaxResultsWithinCeiling() {
        QueryRequest request = new QueryRequest(
                List.of(sel("transaction", "status")), null, List.of(), 500);
        assertThat(validate(request).validatedQuery().orElseThrow().maxResults()).isEqualTo(500);
    }

    @Test
    void rejectsMaxResultsAboveCeiling() {
        QueryRequest request = new QueryRequest(
                List.of(sel("transaction", "status")), null, List.of(), 5000);

        assertThat(validate(request).errors()).anySatisfy(error ->
                assertThat(error.message()).isEqualTo("maxResults must not exceed 1000"));
    }

    // --- happy path ---------------------------------------------------------

    @Test
    void resolvesAValidNestedQuery() {
        FilterNode filter = group(LogicalOperator.AND,
                cond("transaction", "status", "equals", "SETTLED"),
                cond("transaction", "amount", "greaterThan", 1000000),
                group(LogicalOperator.OR,
                        cond("instrument", "assetClass", "in", List.of("FIXED_INCOME", "EQUITY")),
                        cond("counterparty", "country", "equals", "CL")));

        QueryRequest request = new QueryRequest(
                List.of(sel("transaction", "status"), sel("transaction", "amount"),
                        sel("instrument", "assetClass"), sel("counterparty", "country")),
                filter,
                List.of(new Sorting("transaction", "txnDate", SortDirection.DESC)),
                500);

        ValidationResult result = validate(request);
        assertThat(result.isValid()).isTrue();

        ValidatedQuery query = result.validatedQuery().orElseThrow();

        // logical -> physical mapping came from the catalog, in select order
        assertThat(query.select()).extracting(ResolvedField::field).extracting(FieldMeta::physicalName)
                .containsExactly("STATUS", "AMOUNT", "ASSET_CLASS", "COUNTRY");

        // first-appearance order feeds the join resolver
        assertThat(query.referencedEntities()).containsExactly("transaction", "instrument", "counterparty");

        // the filter Composite is preserved and typed
        ResolvedGroup root = (ResolvedGroup) query.filter();
        assertThat(root.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(root.children()).hasSize(3);
        assertThat(query.maxResults()).isEqualTo(500);
    }

    // --- helpers ------------------------------------------------------------

    private ValidationResult validate(QueryRequest request) {
        return validator.validate(request, catalog);
    }

    /** Validates a one-condition query (with a valid select) and asserts it is valid. */
    private ValidatedQuery validateValid(FilterCondition condition) {
        ValidationResult result = validate(request(List.of(sel(condition.entity(), fieldForSelect(condition))), condition));
        assertThat(result.isValid()).as("expected a valid query, got %s", result.errors()).isTrue();
        return result.validatedQuery().orElseThrow();
    }

    /** Picks a selectable field on the condition's entity so validateValid always has a legal select. */
    private String fieldForSelect(FilterCondition condition) {
        return condition.entity().equals("transaction") ? "status" : condition.field();
    }

    private static ResolvedCondition onlyCondition(ValidatedQuery query) {
        return (ResolvedCondition) query.filter();
    }

    private static QueryRequest request(List<SelectField> select, FilterNode filter) {
        return new QueryRequest(select, filter, List.of(), null);
    }

    private static SelectField sel(String entity, String field) {
        return new SelectField(entity, field, null);
    }

    private static FilterCondition cond(String entity, String field, String comparator, Object value) {
        return new FilterCondition(entity, field, comparator, value);
    }

    private static FilterGroup group(LogicalOperator operator, FilterNode... nodes) {
        return new FilterGroup(operator, List.of(nodes));
    }

    private static MetadataCatalog buildCatalog() {
        FieldMeta txnId = new FieldMeta("txnId", "TXN_ID", NUMBER, true, Optional.empty(), true, true);
        FieldMeta status = field("status", "STATUS", STRING);
        FieldMeta amount = field("amount", "AMOUNT", NUMBER);
        FieldMeta txnDate = field("txnDate", "TXN_DATE", DATE);
        FieldMeta createdAt = field("createdAt", "CREATED_AT", TIMESTAMP);
        FieldMeta internalId = new FieldMeta("internalId", "INTERNAL_ID", NUMBER, false, Optional.empty(), true, false);
        FieldMeta secretFlag = new FieldMeta("secretFlag", "SECRET_FLAG", STRING, false, Optional.empty(), false, true);
        FieldMeta counterpartyId = new FieldMeta("counterpartyId", "COUNTERPARTY_ID", NUMBER, false,
                Optional.of(new ForeignKey("party", "partyId")), true, true);
        FieldMeta instrumentIdTx = new FieldMeta("instrumentId", "INSTRUMENT_ID", NUMBER, false,
                Optional.of(new ForeignKey("instrument", "instrumentId")), true, true);

        RelationMeta counterparty = new RelationMeta(
                "counterparty", "transaction", "counterpartyId", "party", "partyId", JoinType.LEFT);
        RelationMeta instrumentRel = new RelationMeta(
                "instrument", "transaction", "instrumentId", "instrument", "instrumentId", JoinType.LEFT);
        RelationMeta issuer = new RelationMeta(
                "issuer", "instrument", "issuerId", "party", "partyId", JoinType.LEFT);

        EntityMeta transaction = new EntityMeta("transaction", "TRANSACTION", "t", "trades",
                List.of(txnId, status, amount, txnDate, createdAt, internalId, secretFlag, counterpartyId, instrumentIdTx),
                List.of(counterparty, instrumentRel));
        EntityMeta instrument = new EntityMeta("instrument", "INSTRUMENT", "i", null,
                List.of(new FieldMeta("instrumentId", "INSTRUMENT_ID", NUMBER, true, Optional.empty(), true, true),
                        field("assetClass", "ASSET_CLASS", STRING)),
                List.of(issuer));
        EntityMeta party = new EntityMeta("party", "PARTY", "p", null,
                List.of(new FieldMeta("partyId", "PARTY_ID", NUMBER, true, Optional.empty(), true, true),
                        field("country", "COUNTRY", STRING),
                        field("partyName", "PARTY_NAME", STRING)),
                List.of());

        return new InMemoryMetadataCatalog(
                List.of(transaction, instrument, party),
                List.of(counterparty, instrumentRel, issuer),
                comparatorMatrix());
    }

    private static FieldMeta field(String name, String physical, com.vmetrix.querymanager.domain.metadata.DataType type) {
        return new FieldMeta(name, physical, type, false, Optional.empty(), true, true);
    }

    private static Map<com.vmetrix.querymanager.domain.metadata.DataType, Set<Comparator>> comparatorMatrix() {
        Map<com.vmetrix.querymanager.domain.metadata.DataType, Set<Comparator>> matrix =
                new EnumMap<>(com.vmetrix.querymanager.domain.metadata.DataType.class);
        matrix.put(STRING, EnumSet.of(EQUALS, NOT_EQUALS, LIKE, IN, NOT_IN, IS_NULL, IS_NOT_NULL));
        matrix.put(NUMBER, EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL,
                LESS_OR_EQUAL, BETWEEN, IN, IS_NULL, IS_NOT_NULL));
        matrix.put(DATE, EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL,
                LESS_OR_EQUAL, BETWEEN, IS_NULL, IS_NOT_NULL));
        matrix.put(TIMESTAMP, EnumSet.of(EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL,
                LESS_OR_EQUAL, IS_NULL, IS_NOT_NULL));
        return matrix;
    }
}
