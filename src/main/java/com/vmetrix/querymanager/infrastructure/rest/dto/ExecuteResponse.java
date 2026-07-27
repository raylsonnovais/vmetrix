package com.vmetrix.querymanager.infrastructure.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * Response body of {@code POST /api/query/execute}. It <em>composes</em> the {@link BuildResponse}
 * (so the generated SQL, parameters and resolution details stay visible) and adds the outcome of
 * running it. Each row is keyed by the field's output alias when given, otherwise its logical
 * camelCase name — never the raw physical column.
 *
 * @param query    the build result (SQL, parameters, resolved tables/joins, metadata)
 * @param rowCount number of rows returned
 * @param rows     the rows, each a column-name → value map
 */
public record ExecuteResponse(BuildResponse query, int rowCount, List<Map<String, Object>> rows) {
}
