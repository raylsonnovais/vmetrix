package com.vmetrix.querymanager.application.join;

import com.vmetrix.querymanager.application.join.JoinPlan.JoinClause;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.metadata.RelationMeta;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link JoinResolver}: turns the referenced query-facing entities into a {@link JoinPlan},
 * treating each relation (alias) as the graph edge rather than the table.
 *
 * <p>The root is derived from the relation graph itself, not from hardcoded entity names: among the
 * base entities that can reach every referenced name, the <em>most specific</em> one is chosen (the
 * one reaching the fewest entities), so an ancestor table like {@code TRANSACTION} is never pulled in
 * unless the query actually needs it. Intermediate joins are inserted for transitive edges, the same
 * table joined twice gets distinct aliases, and a referenced entity is joined once.
 *
 * <p>The resolver never invents a path: if no single root reaches everything, or a base entity is
 * reachable by more than one relation, it throws {@link JoinResolutionException}.
 */
@Component
public class DefaultJoinResolver implements JoinResolver {

    @Override
    public JoinPlan resolve(List<String> referencedEntities, MetadataCatalog catalog) {
        if (referencedEntities.isEmpty()) {
            throw new JoinResolutionException("Cannot resolve joins: no entities were referenced");
        }
        return new PlanBuilder(catalog).build(referencedEntities);
    }

    /** Holds the mutable state for a single {@code resolve} call, keeping the resolver stateless. */
    private static final class PlanBuilder {

        private final MetadataCatalog catalog;
        private final Map<String, String> aliasByEntity = new LinkedHashMap<>();
        private final Set<String> usedAliases = new LinkedHashSet<>();
        private final List<JoinClause> joins = new ArrayList<>();
        private final Set<String> placedRelations = new HashSet<>();
        private final LinkedHashSet<String> resolvedTables = new LinkedHashSet<>();
        private String root;
        private String rootAlias;
        private Set<String> reachable; // base entities reachable from the chosen root

        private PlanBuilder(MetadataCatalog catalog) {
            this.catalog = catalog;
        }

        private JoinPlan build(List<String> referencedEntities) {
            root = determineRoot(referencedEntities);
            reachable = reachableFrom(root);
            EntityMeta rootMeta = baseEntity(root);
            rootAlias = assignAlias(rootMeta.defaultAlias());
            aliasByEntity.put(root, rootAlias);
            resolvedTables.add(rootMeta.physicalTable());

            for (String name : referencedEntities) {
                placeEntity(name);
            }
            return new JoinPlan(root, rootMeta.physicalTable(), rootAlias, joins, aliasByEntity,
                    new ArrayList<>(resolvedTables));
        }

        // --- root selection -------------------------------------------------

        private String determineRoot(List<String> referencedEntities) {
            // Prefer the most specific base entity (the one reaching the fewest entities) that can
            // still reach every referenced name — this avoids dragging in an unneeded ancestor table.
            Map<String, Integer> reach = new HashMap<>();
            for (EntityMeta entity : catalog.entities()) {
                reach.put(entity.name(), reachableFrom(entity.name()).size());
            }
            List<EntityMeta> candidates = new ArrayList<>(catalog.entities());
            candidates.sort(Comparator.comparingInt(entity -> reach.get(entity.name())));

            for (EntityMeta candidate : candidates) {
                if (referencedEntities.stream().allMatch(name -> satisfiable(candidate.name(), name))) {
                    return candidate.name();
                }
            }
            throw new JoinResolutionException(
                    "Cannot determine a single root that reaches all referenced entities: " + referencedEntities);
        }

        /** Base entities reachable from {@code root} by following relations (source reached → target reached). */
        private Set<String> reachableFrom(String root) {
            Set<String> reached = new HashSet<>();
            reached.add(root);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (RelationMeta relation : catalog.relations()) {
                    if (reached.contains(relation.sourceEntity()) && reached.add(relation.targetEntity())) {
                        changed = true;
                    }
                }
            }
            return reached;
        }

        /** Whether a query-facing name can be placed given a chosen root. */
        private boolean satisfiable(String root, String name) {
            if (name.equals(root)) {
                return true;
            }
            Optional<RelationMeta> relation = catalog.findRelation(name);
            if (relation.isPresent()) {
                return reachableFrom(root).contains(relation.get().sourceEntity());
            }
            // A base entity referenced directly (non-root) needs exactly one relation that targets it
            // whose source is reachable — otherwise it is unreachable or ambiguous.
            return relationsTargeting(name, reachableFrom(root)).size() == 1;
        }

        /** Relations whose target is {@code entity} and whose source is in {@code reachableSources}. */
        private List<RelationMeta> relationsTargeting(String entity, Set<String> reachableSources) {
            return catalog.relations().stream()
                    .filter(relation -> relation.targetEntity().equals(entity)
                            && reachableSources.contains(relation.sourceEntity()))
                    .toList();
        }

        // --- placement ------------------------------------------------------

        private void placeEntity(String name) {
            if (aliasByEntity.containsKey(name)) {
                return; // root, or an already-placed relation/base (dedup)
            }
            Optional<RelationMeta> relation = catalog.findRelation(name);
            RelationMeta edge = relation.orElseGet(() -> uniqueRelationTargeting(name));
            aliasByEntity.put(name, placeRelation(edge));
        }

        /** Ensures the relation (and, recursively, its source) is in the plan; returns its target alias. */
        private String placeRelation(RelationMeta relation) {
            if (placedRelations.contains(relation.alias())) {
                return aliasByEntity.get(relation.alias());
            }
            String sourceAlias = relation.sourceEntity().equals(root)
                    ? rootAlias
                    : placeRelation(uniqueRelationTargeting(relation.sourceEntity()));

            EntityMeta target = baseEntity(relation.targetEntity());
            String targetAlias = assignAlias(target.defaultAlias());
            joins.add(new JoinClause(
                    relation.alias(),
                    relation.joinType(),
                    target.physicalTable(),
                    targetAlias,
                    sourceAlias,
                    physicalColumn(relation.sourceEntity(), relation.sourceField()),
                    physicalColumn(relation.targetEntity(), relation.targetField())));
            resolvedTables.add(target.physicalTable());
            placedRelations.add(relation.alias());
            aliasByEntity.put(relation.alias(), targetAlias);
            return targetAlias;
        }

        // --- helpers --------------------------------------------------------

        private RelationMeta uniqueRelationTargeting(String entity) {
            // Reachability-aware, mirroring satisfiable(): only relations whose source is reachable
            // from the chosen root count, so a path that is unique *from here* is not mistaken for
            // ambiguous just because the entity has other, unreachable incoming relations.
            List<RelationMeta> viable = relationsTargeting(entity, reachable);
            if (viable.size() != 1) {
                throw new JoinResolutionException("Cannot reach entity '" + entity + "' from root '" + root + "': "
                        + (viable.isEmpty()
                        ? "no reachable relation targets it"
                        : "it is reachable by more than one relation (ambiguous)"));
            }
            return viable.get(0);
        }

        private String assignAlias(String base) {
            if (usedAliases.add(base)) {
                return base;
            }
            int suffix = 2;
            while (!usedAliases.add(base + suffix)) {
                suffix++;
            }
            return base + suffix;
        }

        private EntityMeta baseEntity(String name) {
            return catalog.findEntity(name)
                    .orElseThrow(() -> new JoinResolutionException("'" + name + "' is not a base entity"));
        }

        private String physicalColumn(String entity, String field) {
            return catalog.findEntity(entity)
                    .flatMap(meta -> meta.findField(field))
                    .map(FieldMeta::physicalName)
                    .orElseThrow(() -> new JoinResolutionException(
                            "No physical column for " + entity + "." + field));
        }
    }
}
