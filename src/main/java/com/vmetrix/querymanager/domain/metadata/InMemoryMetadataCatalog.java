package com.vmetrix.querymanager.domain.metadata;

import com.vmetrix.querymanager.domain.query.Comparator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, map-backed implementation of {@link MetadataCatalog}.
 *
 * <p>Built once from fully-assembled {@link EntityMeta}/{@link RelationMeta} objects (typically by a
 * {@link com.vmetrix.querymanager.infrastructure.metadata.MetadataLoader}) and never mutated
 * afterwards, so a single instance is safe to share across concurrent requests. All collections are
 * defensively copied and exposed read-only.
 */
public final class InMemoryMetadataCatalog implements MetadataCatalog {

    private final List<EntityMeta> entities;
    private final List<RelationMeta> relations;
    private final Map<String, EntityMeta> entitiesByName;
    private final Map<String, RelationMeta> relationsByAlias;
    private final Map<DataType, Set<Comparator>> comparatorsByType;

    /**
     * @param entities          entities (each already carrying its fields and outgoing relations)
     * @param relations         all relations, in a stable order
     * @param comparatorsByType comparators valid per data type (the {@code META_COMPARATOR} matrix)
     */
    public InMemoryMetadataCatalog(
            List<EntityMeta> entities,
            List<RelationMeta> relations,
            Map<DataType, Set<Comparator>> comparatorsByType) {

        this.entities = List.copyOf(entities);
        this.relations = List.copyOf(relations);

        Map<String, EntityMeta> byName = new LinkedHashMap<>();
        for (EntityMeta entity : this.entities) {
            byName.put(entity.name(), entity);
        }
        this.entitiesByName = byName;

        Map<String, RelationMeta> byAlias = new LinkedHashMap<>();
        for (RelationMeta relation : this.relations) {
            byAlias.put(relation.alias(), relation);
        }
        this.relationsByAlias = byAlias;

        Map<DataType, Set<Comparator>> matrix = new EnumMap<>(DataType.class);
        comparatorsByType.forEach((type, comparators) ->
                matrix.put(type, Collections.unmodifiableSet(new LinkedHashSet<>(comparators))));
        this.comparatorsByType = matrix;
    }

    @Override
    public List<EntityMeta> entities() {
        return entities;
    }

    @Override
    public List<RelationMeta> relations() {
        return relations;
    }

    @Override
    public Optional<EntityMeta> findEntity(String name) {
        return Optional.ofNullable(entitiesByName.get(name));
    }

    @Override
    public Optional<RelationMeta> findRelation(String alias) {
        return Optional.ofNullable(relationsByAlias.get(alias));
    }

    @Override
    public Optional<EntityMeta> resolveTargetEntity(String name) {
        EntityMeta base = entitiesByName.get(name);
        if (base != null) {
            return Optional.of(base);
        }
        RelationMeta relation = relationsByAlias.get(name);
        if (relation != null) {
            return findEntity(relation.targetEntity());
        }
        return Optional.empty();
    }

    @Override
    public Set<Comparator> comparatorsFor(DataType type) {
        return comparatorsByType.getOrDefault(type, Set.of());
    }

    @Override
    public boolean supports(DataType type, Comparator comparator) {
        return comparatorsFor(type).contains(comparator);
    }
}
