package com.vmetrix.querymanager.application.join;

import com.vmetrix.querymanager.application.join.JoinPlan.JoinClause;
import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta.ForeignKey;
import com.vmetrix.querymanager.domain.metadata.InMemoryMetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.JoinType;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DefaultJoinResolver}: relation-as-edge behaviour, transitive joins, the same
 * table (PARTY) joined twice with distinct aliases, deduplication, root selection with and without
 * transaction, and deterministic output.
 */
class DefaultJoinResolverTest {

    private DefaultJoinResolver resolver;
    private MetadataCatalog catalog;

    @BeforeEach
    void setUp() {
        resolver = new DefaultJoinResolver();
        catalog = buildCatalog();
    }

    @Test
    void resolvesADirectJoin() {
        JoinPlan plan = resolver.resolve(List.of("transaction", "instrument"), catalog);

        assertThat(plan.rootEntity()).isEqualTo("transaction");
        assertThat(plan.rootTable()).isEqualTo("TRANSACTION");
        assertThat(plan.rootAlias()).isEqualTo("t");
        assertThat(plan.joins()).extracting(JoinClause::relationAlias).containsExactly("instrument");

        JoinClause join = joinFor(plan, "instrument");
        assertThat(join.joinType()).isEqualTo(JoinType.LEFT);
        assertThat(join.targetTable()).isEqualTo("INSTRUMENT");
        assertThat(join.targetAlias()).isEqualTo("i");
        assertThat(join.sourceAlias()).isEqualTo("t");
        assertThat(join.sourceColumn()).isEqualTo("INSTRUMENT_ID");
        assertThat(join.targetColumn()).isEqualTo("INSTRUMENT_ID");

        assertThat(plan.resolvedTables()).containsExactly("TRANSACTION", "INSTRUMENT");
        assertThat(plan.aliasByEntity()).containsEntry("transaction", "t").containsEntry("instrument", "i");
    }

    @Test
    void insertsTransitiveJoinWhenIntermediateIsNotSelected() {
        // issuer hangs off instrument, but no instrument field is referenced
        JoinPlan plan = resolver.resolve(List.of("transaction", "issuer"), catalog);

        assertThat(plan.joins()).extracting(JoinClause::relationAlias)
                .containsExactly("instrument", "issuer"); // INSTRUMENT inserted before PARTY(issuer)

        JoinClause issuer = joinFor(plan, "issuer");
        assertThat(issuer.sourceAlias()).isEqualTo("i");
        assertThat(issuer.sourceColumn()).isEqualTo("ISSUER_ID");
        assertThat(issuer.targetTable()).isEqualTo("PARTY");
        assertThat(issuer.targetAlias()).isEqualTo("p");
        assertThat(issuer.targetColumn()).isEqualTo("PARTY_ID");

        assertThat(plan.resolvedTables()).containsExactly("TRANSACTION", "INSTRUMENT", "PARTY");
        assertThat(plan.aliasByEntity()).containsEntry("instrument", "i").containsEntry("issuer", "p");
    }

    @Test
    void joinsPartyTwiceWithDistinctAliases() {
        JoinPlan plan = resolver.resolve(List.of("transaction", "counterparty", "issuer"), catalog);

        assertThat(plan.joins()).extracting(JoinClause::relationAlias)
                .containsExactly("counterparty", "instrument", "issuer");

        JoinClause counterparty = joinFor(plan, "counterparty");
        JoinClause issuer = joinFor(plan, "issuer");

        // same physical table, different SQL aliases
        assertThat(counterparty.targetTable()).isEqualTo("PARTY");
        assertThat(issuer.targetTable()).isEqualTo("PARTY");
        assertThat(counterparty.targetAlias()).isEqualTo("p");
        assertThat(issuer.targetAlias()).isEqualTo("p2");

        // each hangs off a different source
        assertThat(counterparty.sourceAlias()).isEqualTo("t");
        assertThat(counterparty.sourceColumn()).isEqualTo("COUNTERPARTY_ID");
        assertThat(issuer.sourceAlias()).isEqualTo("i");
        assertThat(issuer.sourceColumn()).isEqualTo("ISSUER_ID");

        assertThat(plan.aliasByEntity())
                .containsEntry("counterparty", "p")
                .containsEntry("issuer", "p2");
        assertThat(plan.resolvedTables()).containsExactly("TRANSACTION", "PARTY", "INSTRUMENT");
    }

    @Test
    void deduplicatesARepeatedEntity() {
        JoinPlan plan = resolver.resolve(List.of("transaction", "instrument", "instrument"), catalog);

        assertThat(plan.joins()).hasSize(1);
        assertThat(plan.joins()).extracting(JoinClause::relationAlias).containsExactly("instrument");
    }

    @Test
    void picksInstrumentAsRootWhenTransactionIsAbsent() {
        JoinPlan plan = resolver.resolve(List.of("instrument", "issuer"), catalog);

        assertThat(plan.rootEntity()).isEqualTo("instrument");
        assertThat(plan.rootAlias()).isEqualTo("i");
        assertThat(plan.joins()).extracting(JoinClause::relationAlias).containsExactly("issuer");

        JoinClause issuer = joinFor(plan, "issuer");
        assertThat(issuer.sourceAlias()).isEqualTo("i");
        assertThat(issuer.targetAlias()).isEqualTo("p");
        assertThat(plan.resolvedTables()).containsExactly("INSTRUMENT", "PARTY");
    }

    @Test
    void infersTransactionRootFromARelationThatHangsOffIt() {
        // counterparty can only be reached from transaction, so transaction becomes the root
        JoinPlan plan = resolver.resolve(List.of("counterparty"), catalog);

        assertThat(plan.rootEntity()).isEqualTo("transaction");
        assertThat(plan.joins()).extracting(JoinClause::relationAlias).containsExactly("counterparty");
    }

    @Test
    void ordersJoinsByFirstAppearanceMatchingTheSpecExample() {
        // the spec's example references instrument before counterparty
        JoinPlan plan = resolver.resolve(List.of("transaction", "instrument", "counterparty"), catalog);

        assertThat(plan.joins()).extracting(JoinClause::relationAlias)
                .containsExactly("instrument", "counterparty");
        assertThat(joinFor(plan, "counterparty").targetAlias()).isEqualTo("p"); // single PARTY join → p
        assertThat(plan.resolvedTables()).containsExactly("TRANSACTION", "INSTRUMENT", "PARTY");
    }

    @Test
    void resolvesPartyViaIssuerWhenReachableByOnlyOnePathFromRoot() {
        // regression: from instrument, party is reachable ONLY via issuer — a unique path.
        // The lookup must be reachability-aware, or it wrongly reports "ambiguous".
        JoinPlan plan = resolver.resolve(List.of("instrument", "party"), catalog);

        assertThat(plan.rootEntity()).isEqualTo("instrument");
        assertThat(plan.joins()).extracting(JoinClause::relationAlias).containsExactly("issuer");

        JoinClause join = joinFor(plan, "issuer");
        assertThat(join.sourceAlias()).isEqualTo("i");
        assertThat(join.sourceColumn()).isEqualTo("ISSUER_ID");
        assertThat(join.targetTable()).isEqualTo("PARTY");
        assertThat(join.targetAlias()).isEqualTo("p");
        assertThat(join.targetColumn()).isEqualTo("PARTY_ID");
        assertThat(plan.aliasByEntity()).containsEntry("party", "p");
    }

    @Test
    void refusesGenuinelyAmbiguousPartyReference() {
        // from transaction, party is reachable via BOTH counterparty and issuer — genuinely ambiguous.
        // The message names the entity and suggests the relation aliases (from the catalog) to use.
        assertThatThrownBy(() -> resolver.resolve(List.of("transaction", "party"), catalog))
                .isInstanceOf(JoinResolutionException.class)
                .hasMessageContaining("'party' is reachable through more than one relation from 'transaction'")
                .hasMessageContaining("counterparty")
                .hasMessageContaining("issuer");
    }

    @Test
    void resolvesPartyAloneAsRootWithNoJoins() {
        JoinPlan plan = resolver.resolve(List.of("party"), catalog);

        assertThat(plan.rootEntity()).isEqualTo("party");
        assertThat(plan.rootAlias()).isEqualTo("p");
        assertThat(plan.joins()).isEmpty();
        assertThat(plan.resolvedTables()).containsExactly("PARTY");
    }

    @Test
    void producesDeterministicOutput() {
        List<String> referenced = List.of("transaction", "counterparty", "issuer");

        JoinPlan first = resolver.resolve(referenced, catalog);
        JoinPlan second = resolver.resolve(referenced, catalog);

        assertThat(first).isEqualTo(second); // records compare by value: same joins, aliases, order
    }

    // --- helpers ------------------------------------------------------------

    private static JoinClause joinFor(JoinPlan plan, String relationAlias) {
        return plan.joins().stream()
                .filter(join -> join.relationAlias().equals(relationAlias))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no join for relation " + relationAlias));
    }

    private static MetadataCatalog buildCatalog() {
        FieldMeta txnId = pk("txnId", "TXN_ID");
        FieldMeta instrumentIdTx = fk("instrumentId", "INSTRUMENT_ID", "instrument", "instrumentId");
        FieldMeta counterpartyId = fk("counterpartyId", "COUNTERPARTY_ID", "party", "partyId");

        FieldMeta instrumentIdPk = pk("instrumentId", "INSTRUMENT_ID");
        FieldMeta issuerId = fk("issuerId", "ISSUER_ID", "party", "partyId");

        FieldMeta partyIdPk = pk("partyId", "PARTY_ID");
        FieldMeta partyName = new FieldMeta("partyName", "PARTY_NAME", DataType.STRING, false,
                Optional.empty(), true, true);

        RelationMeta instrumentRel = new RelationMeta(
                "instrument", "transaction", "instrumentId", "instrument", "instrumentId", JoinType.LEFT);
        RelationMeta counterpartyRel = new RelationMeta(
                "counterparty", "transaction", "counterpartyId", "party", "partyId", JoinType.LEFT);
        RelationMeta issuerRel = new RelationMeta(
                "issuer", "instrument", "issuerId", "party", "partyId", JoinType.LEFT);

        EntityMeta transaction = new EntityMeta("transaction", "TRANSACTION", "t", null,
                List.of(txnId, instrumentIdTx, counterpartyId), List.of(instrumentRel, counterpartyRel));
        EntityMeta instrument = new EntityMeta("instrument", "INSTRUMENT", "i", null,
                List.of(instrumentIdPk, issuerId), List.of(issuerRel));
        EntityMeta party = new EntityMeta("party", "PARTY", "p", null,
                List.of(partyIdPk, partyName), List.of());

        return new InMemoryMetadataCatalog(
                List.of(transaction, instrument, party),
                List.of(instrumentRel, counterpartyRel, issuerRel),
                new EnumMap<>(DataType.class)); // comparator matrix is irrelevant to join resolution
    }

    private static FieldMeta pk(String name, String physical) {
        return new FieldMeta(name, physical, DataType.NUMBER, true, Optional.empty(), true, true);
    }

    private static FieldMeta fk(String name, String physical, String targetEntity, String targetField) {
        return new FieldMeta(name, physical, DataType.NUMBER, false,
                Optional.of(new ForeignKey(targetEntity, targetField)), true, true);
    }
}
