package com.vmetrix.querymanager.application.sql;

import java.util.List;
import java.util.Map;

/**
 * The product of SQL generation: the SQL text plus everything the API response exposes about it.
 *
 * <p>All filter values live in {@link #parameters()} as named bind variables ({@code :p1}, {@code :p2}
 * ...) and never appear inline in {@link #sql()} — this is the core SQL-injection defence. The
 * response's {@code metadata.generatedAt} is stamped at the REST boundary, not here, so the domain
 * stays free of wall-clock time.
 *
 * @param sql            the generated SQL using named parameters
 * @param parameters     bind name → typed value ({@code List} for {@code in}/{@code between})
 * @param resolvedTables physical tables involved, in join order
 * @param resolvedJoins  rendered JOIN clauses, in order
 * @param columnCount    number of projected columns
 * @param filterCount    number of leaf conditions in the filter tree
 */
public record GeneratedSql(
        String sql,
        Map<String, Object> parameters,
        List<String> resolvedTables,
        List<String> resolvedJoins,
        int columnCount,
        int filterCount) {

    public GeneratedSql {
        parameters = Map.copyOf(parameters);
        resolvedTables = List.copyOf(resolvedTables);
        resolvedJoins = List.copyOf(resolvedJoins);
    }
}
