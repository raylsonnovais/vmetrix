package com.vmetrix.querymanager.domain.metadata;

import com.vmetrix.querymanager.domain.metadata.FieldMeta.ForeignKey;
import com.vmetrix.querymanager.domain.query.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the in-memory catalog: name resolution (base entity vs relation alias), the
 * comparator matrix, and immutability. Built from hand-made metadata so it exercises the catalog
 * in isolation from JDBC.
 */
class InMemoryMetadataCatalogTest {

    private MetadataCatalog catalog;

    @BeforeEach
    void setUp() {
        FieldMeta txnId = new FieldMeta("txnId", "TXN_ID", DataType.NUMBER, true, Optional.empty(), true, true);
        FieldMeta status = new FieldMeta("status", "STATUS", DataType.STRING, false, Optional.empty(), true, true);
        FieldMeta instrumentId = new FieldMeta("instrumentId", "INSTRUMENT_ID", DataType.NUMBER, false,
                Optional.of(new ForeignKey("instrument", "instrumentId")), true, true);
        FieldMeta partyId = new FieldMeta("partyId", "PARTY_ID", DataType.NUMBER, true, Optional.empty(), true, true);
        FieldMeta partyName = new FieldMeta("partyName", "PARTY_NAME", DataType.STRING, false, Optional.empty(), true, true);

        RelationMeta counterparty = new RelationMeta(
                "counterparty", "transaction", "counterpartyId", "party", "partyId", JoinType.LEFT);

        EntityMeta transaction = new EntityMeta(
                "transaction", "TRANSACTION", "t", "trades",
                List.of(txnId, status, instrumentId), List.of(counterparty));
        EntityMeta party = new EntityMeta(
                "party", "PARTY", "p", null, List.of(partyId, partyName), List.of());

        Map<DataType, Set<Comparator>> matrix = new EnumMap<>(DataType.class);
        matrix.put(DataType.STRING, new LinkedHashSet<>(List.of(Comparator.EQUALS, Comparator.LIKE)));
        matrix.put(DataType.NUMBER,
                new LinkedHashSet<>(List.of(Comparator.EQUALS, Comparator.GREATER_THAN, Comparator.BETWEEN)));

        catalog = new InMemoryMetadataCatalog(List.of(transaction, party), List.of(counterparty), matrix);
    }

    @Test
    void findsKnownEntitiesAndReportsUnknownOnes() {
        assertThat(catalog.findEntity("transaction")).isPresent();
        assertThat(catalog.findEntity("party")).isPresent();
        assertThat(catalog.findEntity("unknownEntity")).isEmpty();
    }

    @Test
    void findsKnownRelationsAndReportsUnknownOnes() {
        assertThat(catalog.findRelation("counterparty")).isPresent();
        assertThat(catalog.findRelation("issuer")).isEmpty();
    }

    @Test
    void resolvesTargetEntityForBaseEntityAndRelationAlias() {
        // a base entity resolves to itself
        assertThat(catalog.resolveTargetEntity("transaction")).map(EntityMeta::name).contains("transaction");
        // a relation alias resolves to the relation's target entity
        assertThat(catalog.resolveTargetEntity("counterparty")).map(EntityMeta::name).contains("party");
        // neither -> empty
        assertThat(catalog.resolveTargetEntity("nope")).isEmpty();
    }

    @Test
    void exposesComparatorMatrixWithStableOrder() {
        assertThat(catalog.comparatorsFor(DataType.STRING))
                .containsExactly(Comparator.EQUALS, Comparator.LIKE);
        assertThat(catalog.comparatorsFor(DataType.NUMBER))
                .containsExactly(Comparator.EQUALS, Comparator.GREATER_THAN, Comparator.BETWEEN);
        // a type with no configured comparators yields an empty set, not null
        assertThat(catalog.comparatorsFor(DataType.TIMESTAMP)).isEmpty();
    }

    @Test
    void supportsChecksTheMatrix() {
        assertThat(catalog.supports(DataType.STRING, Comparator.LIKE)).isTrue();
        assertThat(catalog.supports(DataType.STRING, Comparator.GREATER_THAN)).isFalse();
        assertThat(catalog.supports(DataType.NUMBER, Comparator.BETWEEN)).isTrue();
        assertThat(catalog.supports(DataType.TIMESTAMP, Comparator.EQUALS)).isFalse();
    }

    @Test
    void entityFindsItsFieldsByLogicalName() {
        EntityMeta transaction = catalog.findEntity("transaction").orElseThrow();
        assertThat(transaction.findField("status")).isPresent();
        assertThat(transaction.findField("instrumentId"))
                .flatMap(FieldMeta::foreignKey)
                .map(ForeignKey::targetEntity)
                .contains("instrument");
        assertThat(transaction.findField("doesNotExist")).isEmpty();
    }

    @Test
    void exposesImmutableCollections() {
        assertThat(catalog.entities()).hasSize(2);
        assertThat(catalog.relations()).hasSize(1);
        assertThatThrownBy(() -> catalog.entities().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.comparatorsFor(DataType.STRING).add(Comparator.IN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
