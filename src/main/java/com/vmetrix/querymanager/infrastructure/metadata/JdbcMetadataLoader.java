package com.vmetrix.querymanager.infrastructure.metadata;

import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.InMemoryMetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.JoinType;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import com.vmetrix.querymanager.domain.query.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link MetadataCatalog} by reading the {@code META_*} tables via JDBC.
 *
 * <p>This is the sole component aware that the metadata is stored in a database; the rest of the
 * engine depends only on the {@link MetadataCatalog} abstraction. Rows are read in a deterministic
 * order and every string token ({@code data_type}, {@code join_type}, {@code comparator}) is resolved
 * against its enum here — an unrecognised token means the metadata is misconfigured, so the load
 * fails loudly rather than producing a silently incomplete catalog.
 */
@Component
public class JdbcMetadataLoader implements MetadataLoader {

    private final JdbcTemplate jdbc;

    public JdbcMetadataLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MetadataCatalog load() {
        Map<String, List<FieldMeta>> fieldsByEntity = loadFieldsByEntity();
        List<RelationMeta> relations = loadRelations();
        Map<String, List<RelationMeta>> relationsByEntity = groupRelationsBySource(relations);

        List<EntityMeta> entities = jdbc.query(
                "SELECT ENTITY, PHYSICAL_TABLE, DEFAULT_ALIAS, DESCRIPTION FROM META_ENTITY ORDER BY ENTITY",
                (rs, rowNum) -> {
                    String name = rs.getString("ENTITY");
                    return new EntityMeta(
                            name,
                            rs.getString("PHYSICAL_TABLE"),
                            rs.getString("DEFAULT_ALIAS"),
                            rs.getString("DESCRIPTION"),
                            fieldsByEntity.getOrDefault(name, List.of()),
                            relationsByEntity.getOrDefault(name, List.of()));
                });

        return new InMemoryMetadataCatalog(entities, relations, loadComparatorMatrix());
    }

    private Map<String, List<FieldMeta>> loadFieldsByEntity() {
        Map<String, List<FieldMeta>> byEntity = new LinkedHashMap<>();
        jdbc.query(
                "SELECT ENTITY, NAME, PHYSICAL_NAME, DATA_TYPE, PK, FK_ENTITY, FK_FIELD, FILTERABLE, SELECTABLE "
                        + "FROM META_FIELD ORDER BY ENTITY, NAME",
                rs -> {
                    String entity = rs.getString("ENTITY");
                    String fkEntity = rs.getString("FK_ENTITY");
                    Optional<FieldMeta.ForeignKey> foreignKey = fkEntity == null
                            ? Optional.empty()
                            : Optional.of(new FieldMeta.ForeignKey(fkEntity, rs.getString("FK_FIELD")));
                    FieldMeta field = new FieldMeta(
                            rs.getString("NAME"),
                            rs.getString("PHYSICAL_NAME"),
                            parseDataType(rs.getString("DATA_TYPE"), entity, rs.getString("NAME")),
                            rs.getBoolean("PK"),
                            foreignKey,
                            rs.getBoolean("FILTERABLE"),
                            rs.getBoolean("SELECTABLE"));
                    byEntity.computeIfAbsent(entity, k -> new ArrayList<>()).add(field);
                });
        return byEntity;
    }

    private List<RelationMeta> loadRelations() {
        return jdbc.query(
                "SELECT ALIAS, SOURCE_ENTITY, SOURCE_FIELD, TARGET_ENTITY, TARGET_FIELD, JOIN_TYPE "
                        + "FROM META_RELATION ORDER BY ALIAS",
                (rs, rowNum) -> new RelationMeta(
                        rs.getString("ALIAS"),
                        rs.getString("SOURCE_ENTITY"),
                        rs.getString("SOURCE_FIELD"),
                        rs.getString("TARGET_ENTITY"),
                        rs.getString("TARGET_FIELD"),
                        parseJoinType(rs.getString("JOIN_TYPE"), rs.getString("ALIAS"))));
    }

    private Map<String, List<RelationMeta>> groupRelationsBySource(List<RelationMeta> relations) {
        Map<String, List<RelationMeta>> bySource = new LinkedHashMap<>();
        for (RelationMeta relation : relations) {
            bySource.computeIfAbsent(relation.sourceEntity(), k -> new ArrayList<>()).add(relation);
        }
        return bySource;
    }

    private Map<DataType, Set<Comparator>> loadComparatorMatrix() {
        Map<DataType, Set<Comparator>> matrix = new EnumMap<>(DataType.class);
        jdbc.query(
                "SELECT DATA_TYPE, COMPARATOR FROM META_COMPARATOR ORDER BY DATA_TYPE, COMPARATOR",
                rs -> {
                    DataType type = parseDataType(rs.getString("DATA_TYPE"), "META_COMPARATOR", null);
                    Comparator comparator = parseComparator(rs.getString("COMPARATOR"), type);
                    matrix.computeIfAbsent(type, k -> EnumSet.noneOf(Comparator.class)).add(comparator);
                });
        return matrix;
    }

    private static DataType parseDataType(String token, String entity, String field) {
        return DataType.fromWire(token).orElseThrow(() -> new IllegalStateException(
                "Unknown data type '" + token + "' in metadata for " + entity
                        + (field == null ? "" : "." + field)));
    }

    private static JoinType parseJoinType(String token, String relationAlias) {
        return JoinType.fromWire(token).orElseThrow(() -> new IllegalStateException(
                "Unknown join type '" + token + "' in metadata for relation '" + relationAlias + "'"));
    }

    private static Comparator parseComparator(String token, DataType type) {
        return Comparator.fromWire(token).orElseThrow(() -> new IllegalStateException(
                "Unknown comparator '" + token + "' in metadata for data type '" + type.wireName() + "'"));
    }
}
