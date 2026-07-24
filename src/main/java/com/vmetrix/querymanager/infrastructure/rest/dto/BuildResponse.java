package com.vmetrix.querymanager.infrastructure.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * Response body of {@code POST /api/query/build} (spec 5.1).
 *
 * @param sql            the generated SQL, using named bind parameters
 * @param parameters     bind name → value (never inlined into {@link #sql()})
 * @param resolvedTables physical tables involved, in join order
 * @param resolvedJoins  rendered JOIN clauses, in order
 * @param metadata       summary counts plus the generation timestamp
 */
public record BuildResponse(
        String sql,
        Map<String, Object> parameters,
        List<String> resolvedTables,
        List<String> resolvedJoins,
        QueryMetadata metadata) {

    /**
     * @param columnCount number of projected columns
     * @param filterCount number of leaf filter conditions
     * @param generatedAt ISO-8601 instant when the SQL was generated (stamped at the REST boundary)
     */
    public record QueryMetadata(int columnCount, int filterCount, String generatedAt) {
    }
}
