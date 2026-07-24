package com.vmetrix.querymanager.infrastructure.metadata;

import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import com.vmetrix.querymanager.domain.query.Comparator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: boots the application against the seeded H2 (Oracle mode), so the catalog is
 * built by {@link JdbcMetadataLoader} from the real {@code META_*} tables and cached by the provider.
 * Verifies the logical-to-physical mapping, the relation graph and the comparator matrix end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcMetadataLoaderTest {

    @Autowired
    private MetadataCatalogProvider catalogProvider;

    @Test
    void loadsAllEntitiesWithTheirFields() {
        MetadataCatalog catalog = catalogProvider.current();

        assertThat(catalog.entities()).extracting(EntityMeta::name)
                .containsExactlyInAnyOrder("transaction", "instrument", "party");

        assertThat(catalog.findEntity("transaction").orElseThrow().fields()).hasSize(13);
        assertThat(catalog.findEntity("instrument").orElseThrow().fields()).hasSize(11);
        assertThat(catalog.findEntity("party").orElseThrow().fields()).hasSize(8);
    }

    @Test
    void mapsPhysicalNamesTypesAndKeys() {
        EntityMeta transaction = catalogProvider.current().findEntity("transaction").orElseThrow();

        FieldMeta txnId = transaction.findField("txnId").orElseThrow();
        assertThat(txnId.physicalName()).isEqualTo("TXN_ID");
        assertThat(txnId.dataType()).isEqualTo(DataType.NUMBER);
        assertThat(txnId.primaryKey()).isTrue();

        assertThat(transaction.findField("txnDate").orElseThrow().dataType()).isEqualTo(DataType.DATE);
        assertThat(transaction.findField("currency").orElseThrow().dataType()).isEqualTo(DataType.STRING);
        assertThat(transaction.findField("createdAt").orElseThrow().dataType()).isEqualTo(DataType.TIMESTAMP);
    }

    @Test
    void mapsForeignKeyMetadata() {
        EntityMeta transaction = catalogProvider.current().findEntity("transaction").orElseThrow();

        FieldMeta instrumentId = transaction.findField("instrumentId").orElseThrow();
        assertThat(instrumentId.foreignKey()).isPresent();
        assertThat(instrumentId.foreignKey().orElseThrow().targetEntity()).isEqualTo("instrument");
        assertThat(instrumentId.foreignKey().orElseThrow().targetField()).isEqualTo("instrumentId");

        assertThat(transaction.findField("amount").orElseThrow().foreignKey()).isEmpty();
    }

    @Test
    void loadsRelationsAsEdgesIncludingBothPartyEdges() {
        MetadataCatalog catalog = catalogProvider.current();

        assertThat(catalog.relations()).extracting(RelationMeta::alias)
                .containsExactlyInAnyOrder("instrument", "counterparty", "issuer");

        RelationMeta counterparty = catalog.findRelation("counterparty").orElseThrow();
        assertThat(counterparty.sourceEntity()).isEqualTo("transaction");
        assertThat(counterparty.sourceField()).isEqualTo("counterpartyId");
        assertThat(counterparty.targetEntity()).isEqualTo("party");
        assertThat(counterparty.targetField()).isEqualTo("partyId");

        // issuer hangs off instrument, not transaction — this is what makes it a transitive join later
        assertThat(catalog.findRelation("issuer").orElseThrow().sourceEntity()).isEqualTo("instrument");

        // both counterparty and issuer resolve to the same target entity (party), by different edges
        assertThat(catalog.resolveTargetEntity("counterparty")).map(EntityMeta::name).contains("party");
        assertThat(catalog.resolveTargetEntity("issuer")).map(EntityMeta::name).contains("party");
    }

    @Test
    void loadsTheComparatorMatrixFromMetadata() {
        MetadataCatalog catalog = catalogProvider.current();

        assertThat(catalog.comparatorsFor(DataType.STRING)).hasSize(7);
        assertThat(catalog.comparatorsFor(DataType.NUMBER)).hasSize(10);
        assertThat(catalog.comparatorsFor(DataType.DATE)).hasSize(9);
        assertThat(catalog.comparatorsFor(DataType.TIMESTAMP)).hasSize(8);

        assertThat(catalog.supports(DataType.STRING, Comparator.LIKE)).isTrue();
        assertThat(catalog.supports(DataType.STRING, Comparator.GREATER_THAN)).isFalse();
        assertThat(catalog.supports(DataType.NUMBER, Comparator.BETWEEN)).isTrue();
        assertThat(catalog.supports(DataType.DATE, Comparator.IN)).isFalse();
        assertThat(catalog.supports(DataType.TIMESTAMP, Comparator.BETWEEN)).isFalse();
    }

    @Test
    void reloadRebuildsAFreshEquivalentSnapshot() {
        MetadataCatalog before = catalogProvider.current();

        catalogProvider.reload();
        MetadataCatalog after = catalogProvider.current();

        assertThat(after).isNotSameAs(before);
        assertThat(after.entities()).hasSameSizeAs(before.entities());
        assertThat(after.findEntity("transaction")).isPresent();
    }
}
