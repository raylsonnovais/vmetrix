package com.vmetrix.querymanager.application.join;

import com.vmetrix.querymanager.domain.metadata.JoinType;

import java.util.List;
import java.util.Map;

/**
 * The resolved JOIN structure for a query: the root table, the ordered joins to reach every other
 * referenced entity, the SQL alias assigned to each query-facing entity, and the physical tables
 * involved. The SQL generator turns this structure into the {@code FROM}/{@code JOIN} text and the
 * {@code resolvedTables}/{@code resolvedJoins} of the response.
 *
 * @param rootEntity     the root query-facing entity name (e.g. {@code "transaction"})
 * @param rootTable      the root's physical table
 * @param rootAlias      the root's SQL alias
 * @param joins          joins in dependency-respecting, deterministic order
 * @param aliasByEntity  query-facing entity name → assigned SQL alias (includes the root)
 * @param resolvedTables physical table names involved, in join order (root first)
 */
public record JoinPlan(
        String rootEntity,
        String rootTable,
        String rootAlias,
        List<JoinClause> joins,
        Map<String, String> aliasByEntity,
        List<String> resolvedTables) {

    public JoinPlan {
        joins = List.copyOf(joins);
        aliasByEntity = Map.copyOf(aliasByEntity);
        resolvedTables = List.copyOf(resolvedTables);
    }

    /**
     * One resolved JOIN edge, expressed purely in physical terms so the SQL layer only concatenates —
     * it does no lookups. Renders to, e.g.,
     * {@code LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID}.
     *
     * @param relationAlias  the relation's query-facing alias (for traceability)
     * @param joinType       the join flavour
     * @param targetTable    physical table being joined in
     * @param targetAlias    SQL alias assigned to the joined table
     * @param sourceAlias    SQL alias of the already-present table the join hangs off
     * @param sourceColumn   physical FK column on the source side
     * @param targetColumn   physical key column on the target side
     */
    public record JoinClause(
            String relationAlias,
            JoinType joinType,
            String targetTable,
            String targetAlias,
            String sourceAlias,
            String sourceColumn,
            String targetColumn) {
    }
}
