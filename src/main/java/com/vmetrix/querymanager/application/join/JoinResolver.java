package com.vmetrix.querymanager.application.join;

import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;

import java.util.List;

/**
 * Resolves which JOINs a query needs, from the set of entities it references, using the relationship
 * metadata as a graph whose <strong>edges are relations, not tables</strong>.
 *
 * <p>Key behaviours:
 * <ul>
 *   <li><b>Relation-as-edge:</b> {@code counterparty} and {@code issuer} both target {@code party}
 *       but are different edges, so referencing both joins {@code PARTY} twice with distinct aliases.</li>
 *   <li><b>Transitivity:</b> an edge may hang off an entity that the query never selects
 *       ({@code issuer} depends on {@code instrument}); intermediate joins are inserted automatically.</li>
 *   <li><b>Deterministic root & order:</b> the root is {@code transaction} when referenced, otherwise
 *       {@code instrument}; joins come out in a stable order (first-appearance of each entity, with
 *       any dependency inserted before its dependent) so output is reproducible and testable.</li>
 *   <li><b>Deduplication:</b> an entity referenced by several fields is joined once.</li>
 * </ul>
 *
 * @implNote Aliases follow the target entity's default alias, disambiguated with a numeric suffix
 *           when the same table is joined more than once (e.g. {@code p}, {@code p2}).
 */
public interface JoinResolver {

    /**
     * Builds the join plan for the given referenced entities.
     *
     * @param referencedEntities query-facing entity names in first-appearance order
     * @param catalog            the metadata to resolve relations against
     * @return the ordered, alias-assigned {@link JoinPlan}
     */
    JoinPlan resolve(List<String> referencedEntities, MetadataCatalog catalog);
}
