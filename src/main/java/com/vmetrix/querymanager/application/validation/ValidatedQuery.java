package com.vmetrix.querymanager.application.validation;

import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.query.Comparator;
import com.vmetrix.querymanager.domain.query.LogicalOperator;
import com.vmetrix.querymanager.domain.query.SortDirection;

import java.util.List;

/**
 * A query that has passed validation: every entity/field name has been resolved against the catalog,
 * every comparator is valid for its field's type, and every value has been converted to its typed
 * form. It is a distinct type from {@code QueryRequest} on purpose — the SQL generator accepts only
 * a {@code ValidatedQuery}, so it is impossible, by construction, to generate SQL from unvalidated
 * input.
 *
 * <p>Each resolved node keeps the query-facing entity name (its {@code entityRef}) rather than a SQL
 * alias, because table aliases are assigned later by the join resolver (the same {@code party} table
 * gets different aliases for {@code counterparty} vs {@code issuer}).
 *
 * @param select             resolved projected fields, in order (non-empty)
 * @param filter             resolved filter tree, or {@code null} when the query has no filters
 * @param sorting            resolved {@code ORDER BY} terms, in order
 * @param maxResults         effective row cap (default/ceiling already applied)
 * @param referencedEntities query-facing entity names in first-appearance order, driving JOIN resolution
 */
public record ValidatedQuery(
        List<ResolvedField> select,
        ResolvedFilter filter,
        List<ResolvedSort> sorting,
        int maxResults,
        List<String> referencedEntities) {

    public ValidatedQuery {
        select = List.copyOf(select);
        sorting = List.copyOf(sorting);
        referencedEntities = List.copyOf(referencedEntities);
    }

    /**
     * A resolved projected field.
     *
     * @param entityRef   query-facing entity name (base entity or relation alias)
     * @param field       the resolved field metadata (carries the physical column name)
     * @param outputAlias output column alias to emit, or {@code null} for none
     */
    public record ResolvedField(String entityRef, FieldMeta field, String outputAlias) {
    }

    /**
     * A resolved {@code ORDER BY} term.
     *
     * @param entityRef query-facing entity name
     * @param field     the resolved field metadata
     * @param direction sort direction
     */
    public record ResolvedSort(String entityRef, FieldMeta field, SortDirection direction) {
    }

    /** Resolved counterpart of {@code FilterNode}: same Composite shape, but fully typed. */
    public sealed interface ResolvedFilter permits ResolvedGroup, ResolvedCondition {
    }

    /**
     * Resolved composite node.
     *
     * @param operator how the children combine
     * @param children resolved child nodes
     */
    public record ResolvedGroup(LogicalOperator operator, List<ResolvedFilter> children)
            implements ResolvedFilter {

        public ResolvedGroup {
            children = List.copyOf(children);
        }
    }

    /**
     * Resolved leaf condition.
     *
     * @param entityRef  query-facing entity name
     * @param field      the resolved field metadata
     * @param comparator the comparator to apply
     * @param value      the type-converted value(s): {@code null} for the null-checks, a scalar for
     *                   single-value comparators, or a {@code List} for {@code in}/{@code notIn}/{@code between}
     */
    public record ResolvedCondition(String entityRef, FieldMeta field, Comparator comparator, Object value)
            implements ResolvedFilter {
    }
}
