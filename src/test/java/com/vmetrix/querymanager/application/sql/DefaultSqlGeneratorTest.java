package com.vmetrix.querymanager.application.sql;

import com.vmetrix.querymanager.application.join.DefaultJoinResolver;
import com.vmetrix.querymanager.application.validation.ValidatedQuery;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedCondition;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedField;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedFilter;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedGroup;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedSort;
import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta.ForeignKey;
import com.vmetrix.querymanager.domain.metadata.InMemoryMetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.JoinType;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import com.vmetrix.querymanager.domain.query.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static com.vmetrix.querymanager.domain.query.Comparator.BETWEEN;
import static com.vmetrix.querymanager.domain.query.Comparator.EQUALS;
import static com.vmetrix.querymanager.domain.query.Comparator.GREATER_THAN;
import static com.vmetrix.querymanager.domain.query.Comparator.IN;
import static com.vmetrix.querymanager.domain.query.Comparator.IS_NOT_NULL;
import static com.vmetrix.querymanager.domain.query.Comparator.IS_NULL;
import static com.vmetrix.querymanager.domain.query.Comparator.LIKE;
import static com.vmetrix.querymanager.domain.query.Comparator.NOT_IN;
import static com.vmetrix.querymanager.domain.query.LogicalOperator.AND;
import static com.vmetrix.querymanager.domain.query.LogicalOperator.OR;
import static com.vmetrix.querymanager.domain.query.SortDirection.ASC;
import static com.vmetrix.querymanager.domain.query.SortDirection.DESC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Unit tests for {@link DefaultSqlGenerator}: per-comparator SQL and binds, correct parenthesisation
 * of nested AND/OR groups (including the spec 5.1 example verified in full), aliasing (PARTY joined
 * twice), and a dedicated SQL-injection section proving every value is bound, never interpolated.
 */
class DefaultSqlGeneratorTest {

    private DefaultSqlGenerator generator;
    private MetadataCatalog catalog;

    @BeforeEach
    void setUp() {
        generator = new DefaultSqlGenerator(new DefaultJoinResolver());
        catalog = buildCatalog();
    }

    // --- comparators --------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"EQUALS,=", "NOT_EQUALS,<>", "GREATER_THAN,>", "LESS_THAN,<",
            "GREATER_OR_EQUAL,>=", "LESS_OR_EQUAL,<="})
    void rendersBinaryComparators(Comparator comparator, String operator) {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "amount", comparator, new BigDecimal("100")),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.AMOUNT " + operator + " :p1");
        assertThat(sql.parameters()).containsExactly(entry("p1", new BigDecimal("100")));
    }

    @Test
    void rendersLikeWithVerbatimPattern() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "status", LIKE, "BANCO%"),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.STATUS LIKE :p1");
        assertThat(sql.parameters()).containsExactly(entry("p1", "BANCO%"));
    }

    @Test
    void rendersInAndNotInWithASingleListParameter() {
        GeneratedSql in = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "currency", IN, List.of("CLP", "USD")),
                List.of("transaction")));
        assertThat(in.sql()).contains("t.CURRENCY IN (:p1)");
        assertThat(in.parameters()).containsExactly(entry("p1", List.of("CLP", "USD")));

        GeneratedSql notIn = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "currency", NOT_IN, List.of("CLP")),
                List.of("transaction")));
        assertThat(notIn.sql()).contains("t.CURRENCY NOT IN (:p1)");
        assertThat(notIn.parameters()).containsExactly(entry("p1", List.of("CLP")));
    }

    @Test
    void rendersBetweenWithTwoParameters() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "amount", BETWEEN, List.of(new BigDecimal("100"), new BigDecimal("200"))),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.AMOUNT BETWEEN :p1 AND :p2");
        assertThat(sql.parameters())
                .containsExactly(entry("p1", new BigDecimal("100")), entry("p2", new BigDecimal("200")));
    }

    @Test
    void rendersNullChecksWithoutParameters() {
        GeneratedSql isNull = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "settlementDate", IS_NULL, null),
                List.of("transaction")));
        assertThat(isNull.sql()).contains("t.SETTLEMENT_DATE IS NULL");
        assertThat(isNull.parameters()).isEmpty();

        GeneratedSql isNotNull = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "settlementDate", IS_NOT_NULL, null),
                List.of("transaction")));
        assertThat(isNotNull.sql()).contains("t.SETTLEMENT_DATE IS NOT NULL");
        assertThat(isNotNull.parameters()).isEmpty();
    }

    // --- structure ----------------------------------------------------------

    @Test
    void generatesTheSpecExampleInFull() {
        ValidatedQuery query = new ValidatedQuery(
                List.of(rf("transaction", "txnDate"), rf("transaction", "amount"), rf("transaction", "currency"),
                        rf("instrument", "ticker"), rf("instrument", "instrumentName"),
                        rf("counterparty", "partyName", "counterpartyName")),
                new ResolvedGroup(AND, List.of(
                        rc("transaction", "status", EQUALS, "SETTLED"),
                        rc("transaction", "amount", GREATER_THAN, new BigDecimal("1000000")),
                        new ResolvedGroup(OR, List.of(
                                rc("instrument", "assetClass", IN, List.of("FIXED_INCOME", "EQUITY")),
                                rc("counterparty", "country", EQUALS, "CL"))))),
                List.of(rs("transaction", "txnDate", DESC)),
                500,
                List.of("transaction", "instrument", "counterparty"));

        GeneratedSql sql = generator.generate(query, catalog);

        assertThat(sql.sql()).isEqualTo(
                "SELECT t.TXN_DATE, t.AMOUNT, t.CURRENCY, i.TICKER, i.INSTRUMENT_NAME, "
                        + "p.PARTY_NAME AS \"counterpartyName\" "
                        + "FROM TRANSACTION t "
                        + "LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID "
                        + "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID "
                        + "WHERE t.STATUS = :p1 AND t.AMOUNT > :p2 "
                        + "AND (i.ASSET_CLASS IN (:p3) OR p.COUNTRY = :p4) "
                        + "ORDER BY t.TXN_DATE DESC "
                        + "FETCH FIRST 500 ROWS ONLY");

        assertThat(sql.parameters()).containsExactly(
                entry("p1", "SETTLED"),
                entry("p2", new BigDecimal("1000000")),
                entry("p3", List.of("FIXED_INCOME", "EQUITY")),
                entry("p4", "CL"));

        assertThat(sql.resolvedTables()).containsExactly("TRANSACTION", "INSTRUMENT", "PARTY");
        assertThat(sql.resolvedJoins()).containsExactly(
                "LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID",
                "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID");
        assertThat(sql.columnCount()).isEqualTo(6);
        assertThat(sql.filterCount()).isEqualTo(4);
    }

    @Test
    void numbersParametersSequentiallyAcrossNestedGroups() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                new ResolvedGroup(AND, List.of(
                        rc("transaction", "status", EQUALS, "A"),
                        new ResolvedGroup(OR, List.of(
                                rc("transaction", "currency", EQUALS, "B"),
                                rc("transaction", "amount", GREATER_THAN, new BigDecimal("1")))))),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.STATUS = :p1 AND (t.CURRENCY = :p2 OR t.AMOUNT > :p3)");
        assertThat(sql.parameters()).containsExactly(
                entry("p1", "A"), entry("p2", "B"), entry("p3", new BigDecimal("1")));
    }

    @Test
    void emitsOutputAliasesOnlyWhenPresent() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "amount", "total"), rf("transaction", "status")),
                null,
                List.of("transaction")));

        assertThat(sql.sql()).startsWith("SELECT t.AMOUNT AS \"total\", t.STATUS FROM TRANSACTION t");
    }

    @Test
    void rendersOrderByWithMultipleTermsAndDirections() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                null,
                List.of(rs("transaction", "amount", ASC), rs("transaction", "txnDate", DESC)),
                List.of("transaction")));

        assertThat(sql.sql()).contains("ORDER BY t.AMOUNT ASC, t.TXN_DATE DESC");
    }

    @Test
    void omitsWhereWhenThereAreNoFilters() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                null,
                List.of("transaction")));

        assertThat(sql.sql()).doesNotContain("WHERE");
        assertThat(sql.filterCount()).isZero();
    }

    @Test
    void usesDistinctAliasesWhenPartyIsJoinedTwice() {
        GeneratedSql sql = generate(query(
                List.of(rf("counterparty", "partyName"), rf("issuer", "partyName")),
                null,
                List.of("counterparty", "issuer")));

        // counterparty -> p, issuer -> p2: the entityRef->alias mapping keeps them apart
        assertThat(sql.sql()).contains("p.PARTY_NAME, p2.PARTY_NAME");
        assertThat(sql.resolvedJoins()).contains(
                "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID",
                "LEFT JOIN PARTY p2 ON i.ISSUER_ID = p2.PARTY_ID");
    }

    @Test
    void appliesTheEffectiveMaxResults() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")), null, List.of("transaction")));

        assertThat(sql.sql()).endsWith("FETCH FIRST 100 ROWS ONLY");
    }

    // --- SQL injection safety (values are always bound) ---------------------

    @Test
    void injectionAttemptInValueBecomesABindParameterNotSql() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "status", EQUALS, "' OR 1=1 --"),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.STATUS = :p1");
        assertThat(sql.sql()).doesNotContain("1=1").doesNotContain("OR 1=1");
        assertThat(sql.parameters()).containsExactly(entry("p1", "' OR 1=1 --"));
    }

    @Test
    void semicolonAndDropStayInsideTheBindParameter() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "status", EQUALS, "x'; DROP TABLE PARTY; --"),
                List.of("transaction")));

        assertThat(sql.sql()).doesNotContain("DROP TABLE");
        assertThat(sql.parameters()).containsExactly(entry("p1", "x'; DROP TABLE PARTY; --"));
    }

    @Test
    void likePatternWithWildcardsAndQuotesGoesEntirelyIntoTheParameter() {
        String pattern = "%'; DROP--";
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                rc("transaction", "status", LIKE, pattern),
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.STATUS LIKE :p1").doesNotContain("DROP");
        assertThat(sql.parameters()).containsExactly(entry("p1", pattern));
    }

    @Test
    void outputAliasWithQuotesIsEscapedAndInert() {
        // the output alias is the only identifier that comes from the request; an embedded quote must
        // be doubled so it cannot close the quoted identifier and inject trailing SQL.
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status", "bad\" FROM x --")),
                null,
                List.of("transaction")));

        assertThat(sql.sql()).contains("t.STATUS AS \"bad\"\" FROM x --\"");
        assertThat(sql.sql())
                .startsWith("SELECT t.STATUS AS \"bad\"\" FROM x --\" FROM TRANSACTION t")
                .endsWith("FETCH FIRST 100 ROWS ONLY");
    }

    @Test
    void generatedSqlContainsNoValueLiterals() {
        GeneratedSql sql = generate(query(
                List.of(rf("transaction", "status")),
                new ResolvedGroup(AND, List.of(
                        rc("transaction", "status", EQUALS, "SETTLED"),
                        rc("transaction", "currency", IN, List.of("CLP", "USD")))),
                List.of("transaction")));

        // Every value is bound, so no string literal (single quote) can appear anywhere in the SQL.
        assertThat(sql.sql()).doesNotContain("'");
        assertThat(sql.sql()).contains(":p1").contains(":p2");
        assertThat(sql.parameters()).containsKeys("p1", "p2");
    }

    // --- helpers ------------------------------------------------------------

    private GeneratedSql generate(ValidatedQuery query) {
        return generator.generate(query, catalog);
    }

    private ValidatedQuery query(List<ResolvedField> select, ResolvedFilter filter, List<String> referenced) {
        return new ValidatedQuery(select, filter, List.of(), 100, referenced);
    }

    private ValidatedQuery query(List<ResolvedField> select, ResolvedFilter filter,
                                 List<ResolvedSort> sorting, List<String> referenced) {
        return new ValidatedQuery(select, filter, sorting, 100, referenced);
    }

    private ResolvedField rf(String entity, String field) {
        return new ResolvedField(entity, fieldMeta(entity, field), null);
    }

    private ResolvedField rf(String entity, String field, String alias) {
        return new ResolvedField(entity, fieldMeta(entity, field), alias);
    }

    private ResolvedCondition rc(String entity, String field, Comparator comparator, Object value) {
        return new ResolvedCondition(entity, fieldMeta(entity, field), comparator, value);
    }

    private ResolvedSort rs(String entity, String field, com.vmetrix.querymanager.domain.query.SortDirection dir) {
        return new ResolvedSort(entity, fieldMeta(entity, field), dir);
    }

    private FieldMeta fieldMeta(String entity, String field) {
        return catalog.resolveTargetEntity(entity).orElseThrow().findField(field).orElseThrow();
    }

    private static MetadataCatalog buildCatalog() {
        RelationMeta instrumentRel = new RelationMeta(
                "instrument", "transaction", "instrumentId", "instrument", "instrumentId", JoinType.LEFT);
        RelationMeta counterpartyRel = new RelationMeta(
                "counterparty", "transaction", "counterpartyId", "party", "partyId", JoinType.LEFT);
        RelationMeta issuerRel = new RelationMeta(
                "issuer", "instrument", "issuerId", "party", "partyId", JoinType.LEFT);

        EntityMeta transaction = new EntityMeta("transaction", "TRANSACTION", "t", null,
                List.of(
                        pk("txnId", "TXN_ID"),
                        field("txnDate", "TXN_DATE", DataType.DATE),
                        field("amount", "AMOUNT", DataType.NUMBER),
                        field("currency", "CURRENCY", DataType.STRING),
                        field("status", "STATUS", DataType.STRING),
                        field("settlementDate", "SETTLEMENT_DATE", DataType.DATE),
                        field("createdAt", "CREATED_AT", DataType.TIMESTAMP),
                        fk("instrumentId", "INSTRUMENT_ID", "instrument", "instrumentId"),
                        fk("counterpartyId", "COUNTERPARTY_ID", "party", "partyId")),
                List.of(instrumentRel, counterpartyRel));

        EntityMeta instrument = new EntityMeta("instrument", "INSTRUMENT", "i", null,
                List.of(
                        pk("instrumentId", "INSTRUMENT_ID"),
                        field("ticker", "TICKER", DataType.STRING),
                        field("instrumentName", "INSTRUMENT_NAME", DataType.STRING),
                        field("assetClass", "ASSET_CLASS", DataType.STRING),
                        fk("issuerId", "ISSUER_ID", "party", "partyId")),
                List.of(issuerRel));

        EntityMeta party = new EntityMeta("party", "PARTY", "p", null,
                List.of(
                        pk("partyId", "PARTY_ID"),
                        field("partyName", "PARTY_NAME", DataType.STRING),
                        field("country", "COUNTRY", DataType.STRING)),
                List.of());

        return new InMemoryMetadataCatalog(
                List.of(transaction, instrument, party),
                List.of(instrumentRel, counterpartyRel, issuerRel),
                new EnumMap<>(DataType.class));
    }

    private static FieldMeta pk(String name, String physical) {
        return new FieldMeta(name, physical, DataType.NUMBER, true, Optional.empty(), true, true);
    }

    private static FieldMeta field(String name, String physical, DataType type) {
        return new FieldMeta(name, physical, type, false, Optional.empty(), true, true);
    }

    private static FieldMeta fk(String name, String physical, String targetEntity, String targetField) {
        return new FieldMeta(name, physical, DataType.NUMBER, false,
                Optional.of(new ForeignKey(targetEntity, targetField)), true, true);
    }
}
