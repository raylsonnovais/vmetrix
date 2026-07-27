package com.vmetrix.querymanager.application.sql;

import com.vmetrix.querymanager.application.join.JoinPlan;
import com.vmetrix.querymanager.application.join.JoinPlan.JoinClause;
import com.vmetrix.querymanager.application.join.JoinResolver;
import com.vmetrix.querymanager.application.validation.ValidatedQuery;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedCondition;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedField;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedFilter;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedGroup;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedSort;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link SqlGenerator}: turns a {@link ValidatedQuery} into {@link GeneratedSql}.
 *
 * <p>Joins are delegated to the injected {@link JoinResolver}, which yields a {@link JoinPlan} whose
 * {@code aliasByEntity} maps each query-facing entity reference to its SQL alias. Every column is then
 * simply {@code alias.PHYSICAL_NAME} — no catalog lookup here, the plan already resolved everything.
 *
 * <p><strong>Security:</strong> only physical identifiers from the catalog/plan are concatenated into
 * the SQL. Every filter <em>value</em> is emitted as a named bind parameter ({@code :p1}, {@code :p2},
 * ...), including inside {@code IN} and {@code BETWEEN}; nothing is interpolated. The one identifier
 * that originates from the request — a select field's output alias — is emitted as a double-quoted
 * identifier with embedded quotes escaped, so it cannot break out.
 *
 * <p>Patterns: a <strong>Builder</strong>-style assembly of the clauses, a recursive
 * <strong>Composite</strong> walk of the filter tree, and a per-comparator <strong>Strategy</strong>
 * expressed as a {@code switch} in this layer (the domain {@code Comparator} enum stays SQL-free).
 */
@Component
public class DefaultSqlGenerator implements SqlGenerator {

    private final JoinResolver joinResolver;

    public DefaultSqlGenerator(JoinResolver joinResolver) {
        this.joinResolver = joinResolver;
    }

    @Override
    public GeneratedSql generate(ValidatedQuery query, MetadataCatalog catalog) {
        JoinPlan plan = joinResolver.resolve(query.referencedEntities(), catalog);
        BindAllocator binds = new BindAllocator();

        List<String> renderedJoins = plan.joins().stream().map(DefaultSqlGenerator::renderJoin).toList();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(renderSelect(query.select(), plan));
        sql.append(" FROM ").append(plan.rootTable()).append(' ').append(plan.rootAlias());
        for (String join : renderedJoins) {
            sql.append(' ').append(join);
        }
        if (query.filter() != null) {
            sql.append(" WHERE ").append(renderFilter(query.filter(), false, plan, binds));
        }
        if (!query.sorting().isEmpty()) {
            sql.append(" ORDER BY ").append(renderOrderBy(query.sorting(), plan));
        }
        sql.append(" FETCH FIRST ").append(query.maxResults()).append(" ROWS ONLY");

        return new GeneratedSql(
                sql.toString(),
                binds.parameters(),
                plan.resolvedTables(),
                renderedJoins,
                query.select().size(),
                countConditions(query.filter()));
    }

    // --- SELECT / FROM / JOIN / ORDER BY -----------------------------------

    private static String renderSelect(List<ResolvedField> fields, JoinPlan plan) {
        List<String> columns = new ArrayList<>(fields.size());
        for (ResolvedField field : fields) {
            String column = column(field.entityRef(), field.field(), plan);
            if (field.outputAlias() != null) {
                column += " AS \"" + field.outputAlias().replace("\"", "\"\"") + '"';
            }
            columns.add(column);
        }
        return String.join(", ", columns);
    }

    private static String renderJoin(JoinClause join) {
        return join.joinType().sqlKeyword() + ' ' + join.targetTable() + ' ' + join.targetAlias()
                + " ON " + join.sourceAlias() + '.' + join.sourceColumn()
                + " = " + join.targetAlias() + '.' + join.targetColumn();
    }

    private static String renderOrderBy(List<ResolvedSort> sorts, JoinPlan plan) {
        List<String> terms = new ArrayList<>(sorts.size());
        for (ResolvedSort sort : sorts) {
            terms.add(column(sort.entityRef(), sort.field(), plan) + ' ' + sort.direction().sqlKeyword());
        }
        return String.join(", ", terms);
    }

    // --- WHERE (Composite walk) --------------------------------------------

    private static String renderFilter(ResolvedFilter node, boolean nested, JoinPlan plan, BindAllocator binds) {
        if (node instanceof ResolvedCondition condition) {
            return renderCondition(condition, plan, binds);
        }
        ResolvedGroup group = (ResolvedGroup) node;
        List<String> parts = new ArrayList<>(group.children().size());
        for (ResolvedFilter child : group.children()) {
            parts.add(renderFilter(child, true, plan, binds));
        }
        String joined = String.join(" " + group.operator().name() + " ", parts);
        // Parenthesise a nested group with more than one child so operator precedence is preserved
        // (e.g. a AND (b OR c) never collapses to a AND b OR c). The root group needs no wrapping.
        return nested && group.children().size() > 1 ? "(" + joined + ")" : joined;
    }

    // --- comparator Strategy (SQL fragment + bind allocation) --------------

    private static String renderCondition(ResolvedCondition condition, JoinPlan plan, BindAllocator binds) {
        String column = column(condition.entityRef(), condition.field(), plan);
        return switch (condition.comparator()) {
            case EQUALS -> column + " = " + binds.next(condition.value());
            case NOT_EQUALS -> column + " <> " + binds.next(condition.value());
            case GREATER_THAN -> column + " > " + binds.next(condition.value());
            case LESS_THAN -> column + " < " + binds.next(condition.value());
            case GREATER_OR_EQUAL -> column + " >= " + binds.next(condition.value());
            case LESS_OR_EQUAL -> column + " <= " + binds.next(condition.value());
            case LIKE -> column + " LIKE " + binds.next(condition.value());
            case IN -> column + " IN (" + binds.next(condition.value()) + ")";
            case NOT_IN -> column + " NOT IN (" + binds.next(condition.value()) + ")";
            case BETWEEN -> {
                List<?> range = (List<?>) condition.value();
                yield column + " BETWEEN " + binds.next(range.get(0)) + " AND " + binds.next(range.get(1));
            }
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
        };
    }

    // --- helpers -----------------------------------------------------------

    /** Resolves a query-facing field reference to its {@code alias.PHYSICAL_NAME} using the plan. */
    private static String column(String entityRef, FieldMeta field, JoinPlan plan) {
        String alias = plan.aliasByEntity().get(entityRef);
        if (alias == null) {
            throw new IllegalStateException("No SQL alias resolved for entity reference '" + entityRef + "'");
        }
        return alias + '.' + field.physicalName();
    }

    private static int countConditions(ResolvedFilter node) {
        if (node == null) {
            return 0;
        }
        if (node instanceof ResolvedCondition) {
            return 1;
        }
        int total = 0;
        for (ResolvedFilter child : ((ResolvedGroup) node).children()) {
            total += countConditions(child);
        }
        return total;
    }

    /** Allocates stable, sequential named bind parameters ({@code p1}, {@code p2}, ...) in walk order. */
    private static final class BindAllocator {

        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private int counter;

        /** Binds a value to the next {@code pN} and returns its {@code :pN} placeholder. */
        String next(Object value) {
            String name = "p" + (++counter);
            parameters.put(name, value);
            return ":" + name;
        }

        Map<String, Object> parameters() {
            return parameters;
        }
    }
}
