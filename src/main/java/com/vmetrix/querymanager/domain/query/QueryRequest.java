package com.vmetrix.querymanager.domain.query;

import java.util.List;

/**
 * A high-level, domain-oriented query specification — the clean domain model the engine works with,
 * decoupled from the wire/JSON shape (mapping from the REST payload happens at the boundary).
 *
 * <p>Everything here is still <em>untrusted</em>: names may not exist, comparators may be invalid for
 * their field, values may be malformed. {@code QueryValidator} turns a {@code QueryRequest} into a
 * {@code ValidatedQuery} (or a list of errors); only the validated form can reach SQL generation.
 *
 * @param select     columns to project; must be non-empty for a build
 * @param filters    root of the filter tree, or {@code null} when there are no filters
 * @param sorting    {@code ORDER BY} terms, in order; may be empty
 * @param maxResults caller-requested row cap, or {@code null} to use the configured default
 */
public record QueryRequest(
        List<SelectField> select,
        FilterNode filters,
        List<Sorting> sorting,
        Integer maxResults) {

    public QueryRequest {
        select = select == null ? List.of() : List.copyOf(select);
        sorting = sorting == null ? List.of() : List.copyOf(sorting);
    }
}
